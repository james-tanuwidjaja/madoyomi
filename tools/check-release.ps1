<#
.SYNOPSIS
  Checks a release's files (Phase 7 Stage 5): the ones CI uploads to the draft release, or the draft's assets
  downloaded again (RELEASING.md).

.DESCRIPTION
  In -Dir it expects, for version X.Y.Z (default: VERSION):
    Madoyomi_X.Y.Z_x64-setup.exe, Madoyomi_X.Y.Z_x64-setup.exe.sig, Madoyomi_X.Y.Z_x64_en-US.msi, latest.json
  and, when present, SHA256SUMS.txt. It checks:
    - every file is there and not empty (sizes listed);
    - the NSIS installer's version resource (ProductVersion and FileVersion X.Y.Z, the name, the publisher in the
      copyright line: Tauri's template sets no CompanyName);
    - the MSI's ProductVersion, Manufacturer and UpgradeCode (the one pinned in tauri.conf.json), read through the
      Windows Installer API without installing it;
    - latest.json and the updater signature: node tools\verify-updater.mjs (the signature against tauri.conf.json's
      public key, the URL https://github.com/<Repo>/releases/download/vX.Y.Z/<installer>);
    - SHA256SUMS.txt against the files (-WriteSums writes it first).
  Exits 1 when a check fails. Reads no secret. Keep this file ASCII; it runs on Windows PowerShell 5.1 and 7.

.PARAMETER Dir
  The folder with the release's files.

.PARAMETER Version
  X.Y.Z. Default: the repo's VERSION.

.PARAMETER Repo
  owner/name of the GitHub repo the release is published from. Default: james-tanuwidjaja/madoyomi.

.PARAMETER WriteSums
  Write SHA256SUMS.txt for the four files first (CI does).

.EXAMPLE
  .\tools\check-release.ps1 -Dir $env:TEMP\madoyomi-0.1.0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [string]$Version,
    [string]$Repo = 'james-tanuwidjaja/madoyomi',
    [switch]$WriteSums
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Version) { $Version = (Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'VERSION')).Trim() }
$Dir = (Resolve-Path -LiteralPath $Dir).Path
$script:failed = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    $line = if ($Ok) { 'PASS' } else { 'FAIL' }
    $line += "  $Name"
    if ($Detail) { $line += ": $Detail" }
    Write-Output $line
    if (-not $Ok) { $script:failed++ }
}

$setup = "Madoyomi_${Version}_x64-setup.exe"
$msi = "Madoyomi_${Version}_x64_en-US.msi"
$names = @($setup, "$setup.sig", $msi, 'latest.json')
foreach ($n in $names) {
    $p = Join-Path $Dir $n
    $ok = (Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p).Length -gt 0)
    $size = if (Test-Path -LiteralPath $p) { '{0:N1} MiB ({1} bytes)' -f ((Get-Item -LiteralPath $p).Length / 1MB), (Get-Item -LiteralPath $p).Length } else { 'missing' }
    Check "file $n" $ok $size
}
if ($script:failed -gt 0) { Write-Output "$($script:failed) check(s) failed"; exit 1 }

# The NSIS installer's version resource.
$vi = (Get-Item -LiteralPath (Join-Path $Dir $setup)).VersionInfo
Check 'installer ProductVersion' ($vi.ProductVersion -eq $Version -or $vi.ProductVersion -eq "$Version.0") $vi.ProductVersion
Check 'installer FileVersion' ($vi.FileVersion -eq $Version) $vi.FileVersion
Check 'installer ProductName' ($vi.ProductName -eq 'Madoyomi') $vi.ProductName
# Tauri's NSIS template sets no CompanyName: the publisher is in the copyright line (bundle.copyright)
Check 'installer LegalCopyright' ($vi.LegalCopyright -like '*Madoyomi contributors*') $vi.LegalCopyright

# The MSI's properties, without installing it.
$conf = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'app\src-tauri\tauri.conf.json') | ConvertFrom-Json
$wantUpgrade = '{' + $conf.bundle.windows.wix.upgradeCode.ToUpperInvariant() + '}'
$wi = New-Object -ComObject WindowsInstaller.Installer
$db = $wi.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $wi, @([string](Join-Path $Dir $msi), 0))
$view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @('SELECT Property, Value FROM Property'))
$null = $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
$props = @{}
while ($r = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)) {
    $k = $r.GetType().InvokeMember('StringData', 'GetProperty', $null, $r, @(1))
    $props[$k] = $r.GetType().InvokeMember('StringData', 'GetProperty', $null, $r, @(2))
}
$null = $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
$null = [Runtime.InteropServices.Marshal]::ReleaseComObject($view)
$null = [Runtime.InteropServices.Marshal]::ReleaseComObject($db)
Check 'MSI ProductVersion' ($props['ProductVersion'] -eq $Version) $props['ProductVersion']
Check 'MSI Manufacturer' ($props['Manufacturer'] -eq 'Madoyomi contributors') $props['Manufacturer']
Check 'MSI UpgradeCode' ($props['UpgradeCode'] -eq $wantUpgrade) $props['UpgradeCode']

# latest.json and the updater signature.
$out = & node (Join-Path $PSScriptRoot 'verify-updater.mjs') (Join-Path $Dir 'latest.json') (Join-Path $Dir $setup) --version $Version --repo $Repo 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { Write-Output "  $_" }
Check 'verify-updater.mjs' ($code -eq 0) "exit code $code"

# SHA256SUMS.txt
$sums = Join-Path $Dir 'SHA256SUMS.txt'
if ($WriteSums) {
    $lines = foreach ($n in @($setup, "$setup.sig", $msi, 'latest.json')) {
        '{0}  {1}' -f (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Dir $n)).Hash.ToLowerInvariant(), $n
    }
    [IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
}
if (Test-Path -LiteralPath $sums) {
    $bad = 0; $count = 0
    foreach ($l in (Get-Content -LiteralPath $sums | Where-Object { $_.Trim() })) {
        $hash, $name = $l -split '\s+\*?', 2
        $count++
        $p = Join-Path $Dir $name
        if (-not (Test-Path -LiteralPath $p) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() -ne $hash.ToLowerInvariant()) { $bad++ }
    }
    Check 'SHA256SUMS.txt' ($bad -eq 0 -and $count -eq 4) "$count file(s), $bad mismatch(es)"
} else {
    Write-Output 'skip  SHA256SUMS.txt not in the folder'
}

if ($script:failed -gt 0) { Write-Output "$($script:failed) check(s) failed"; exit 1 }
Write-Output "OK: Madoyomi $Version's release files check out"
