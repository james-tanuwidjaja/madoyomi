<#
.SYNOPSIS
  Restores the upstream binary assets (fonts, test images) that Madoyomi does not commit.

.DESCRIPTION
  server/ is a fork of Suwayomi-Server (see server/UPSTREAM.md). Upstream tracks a few binary
  assets in git; Madoyomi gitignores them and restores them with this script before building.
  Each file is fetched from upstream at the pinned commit (or copied from a local upstream
  checkout) and its SHA-256 is checked. Existing files with the right hash are left alone.

.PARAMETER UpstreamCheckout
  Optional path to a local Suwayomi-Server checkout at the pinned commit. Defaults to
  tools/upstream/Suwayomi-Server if it exists; otherwise files are downloaded from GitHub.

.PARAMETER Force
  Re-fetch even if a file already exists with the right hash.

.EXAMPLE
  .\tools\fetch-upstream-assets.ps1
#>
[CmdletBinding()]
param(
    [string]$UpstreamCheckout,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# Keep in sync with server/UPSTREAM.md.
$UpstreamCommit = '7b061be09c9b62a89a63addd11ac91c40fc2510f'
$RawBase = "https://raw.githubusercontent.com/Suwayomi/Suwayomi-Server/$UpstreamCommit"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'

if (-not $UpstreamCheckout) {
    $default = Join-Path $RepoRoot 'tools/upstream/Suwayomi-Server'
    if (Test-Path (Join-Path $default '.git')) { $UpstreamCheckout = $default }
}
if ($UpstreamCheckout) {
    $head = (git -C $UpstreamCheckout rev-parse HEAD).Trim()
    if ($head -ne $UpstreamCommit) {
        Write-Warning "Local checkout $UpstreamCheckout is at $head, not $UpstreamCommit; downloading from GitHub instead."
        $UpstreamCheckout = $null
    }
}

# Upstream path -> path under server/, plus SHA-256.
# The local path differs from the upstream path where Madoyomi moved a module (see UPSTREAM.md).
$Assets = @(
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoColorEmoji-Regular.ttf'; Sha = '446d81fe164c5d90a66e4c5ec2173a19b0eb639aa617717fc022d21d854bdaec' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoEmoji-VariableFont_wght.ttf'; Sha = '2a9f7623ab96cd6e82f90257ccd1bd4fe66ea982a0acf70d0d7aa1166e2c0885' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSans-Italic-VariableFont_wdth,wght.ttf'; Sha = '4bf7b366af79c434984d67eae3967e9cd7a2f51c196101c43f21a7e21e608844' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSans-VariableFont_wdth,wght.ttf'; Sha = '7b8cac46a1c86d2533a616b1fcf4e1926b8e39bda69034508b0df96791f56d97' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSansMono-VariableFont_wdth,wght.ttf'; Sha = '47d856141683450ee297592b27be447eb5141c68516e5e0e748c66b6e0a54afe' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSansSymbols2-Regular.ttf'; Sha = 'f67f40c65f929f029827ab37cfdc4897dfd9ae3c28d9e98ba46e0d52e39eb97e' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSerif-Italic-VariableFont_wdth,wght.ttf'; Sha = 'cb8557fec7738ede172114f2ca5933e6ca14b10b55dac3cd36abbfbb562abebb' }
    @{ Up = 'AndroidCompat/src/main/resources/font/NotoSans/NotoSerif-VariableFont_wdth,wght.ttf'; Sha = '26485cbd00507110de7a77cd63d0ec99d356f38e5d9c0c5d07ecea1c7827ea24' }
    @{ Up = 'server/src/test/resources/dice.jxl'; Sha = 'cfaf3684efc2348c7886e60c400ad25249a49b9f52b85b163433402b9e95e34a' }
    @{ Up = 'server/src/test/resources/fox.profile0.8bpc.yuv420.avif'; Sha = 'cb884c82ac7b6d4fa03b1f687e9e20abc346107095473e9c1d422aaf0de14eaf' }
    @{ Up = 'server/src/test/resources/sample1.heif'; Sha = '46ac93516093b70bfed23d11f651819a4fb946088a6a933d6a5d34f4fb967bad' }
)

function Get-Sha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$fetched = 0
foreach ($a in $Assets) {
    $local = if ($a.ContainsKey('Local')) { $a.Local } else { $a.Up }
    $dest = Join-Path $ServerDir $local
    if (-not $Force -and (Test-Path -LiteralPath $dest) -and (Get-Sha256 $dest) -eq $a.Sha) {
        Write-Verbose "ok      $local"
        continue
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    $tmp = "$dest.part"
    if ($UpstreamCheckout) {
        Copy-Item -LiteralPath (Join-Path $UpstreamCheckout $a.Up) -Destination $tmp -Force
    } else {
        $url = $RawBase + '/' + (($a.Up -split '/' | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/')
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    }
    $got = Get-Sha256 $tmp
    if ($got -ne $a.Sha) {
        Remove-Item -LiteralPath $tmp -Force
        throw "SHA-256 mismatch for $($a.Up): expected $($a.Sha), got $got"
    }
    Move-Item -LiteralPath $tmp -Destination $dest -Force
    Write-Host "fetched $local"
    $fetched++
}
Write-Host "Upstream assets: $fetched fetched, $($Assets.Count - $fetched) already present (all $($Assets.Count) verified)."
