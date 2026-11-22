<#
.SYNOPSIS
  Downloads the pinned mpv, ffmpeg and yt-dlp builds of tools\binaries.json, checks their SHA-256 and unpacks them
  where release builds bundle them from: app\src-tauri\resources\<dest> (gitignored).

.DESCRIPTION
  For each entry of binaries.json:
    1. the download is kept in tools\bin\downloads (gitignored): a file already there is used again only when its
       SHA-256 matches; otherwise it is downloaded again;
    2. the SHA-256 of the download must equal the pinned one, or the script fails (and deletes the download);
    3. the files in "take" (names or wildcards, matched against the archive's entries, or the downloaded file itself
       when it isn't an archive; "inner" names a zip inside the zip) are copied flat into <Out>\<dest>, after the
       old contents of that folder are removed;
    4. each entry of "licenses" (license texts at the upstream tags) is downloaded into
       tools\bin\downloads\licenses\<name>, checked against its pinned SHA-256 like the download, and copied to
       <Out>\<dest>\<as> (mpv's own at the top, the texts of the libraries in its DLLs under licenses\);
    5. `<dest>.exe <versionArgs>` must run; its first line is printed.
  A stamp (<Out>\.stamps\<dest>.json, outside the bundled folders) skips steps 2-5 when the folder already holds
  the pinned build with the same files and license texts (-Force redoes them).

  The Tauri shell looks for <resources>\<dest>\<dest>.exe (app/src-tauri/src/sidecar.rs, tool_from) and passes it to
  the backend as MADOYOMI_MPV, MADOYOMI_FFMPEG or MADOYOMI_YTDLP. Never commit the downloads or the unpacked files.
  Keep this file ASCII; it runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER Out
  The resources folder. Default: app\src-tauri\resources. tools\bin works too (the debug shell looks in
  tools\bin\<dest>\<dest>.exe).

.PARAMETER Only
  Only these entries (by name).

.EXAMPLE
  .\tools\fetch-binaries.ps1
.EXAMPLE
  .\tools\fetch-binaries.ps1 -Only yt-dlp -Force
#>
[CmdletBinding()]
param(
    [string]$Out,
    [string[]]$Only,
    [switch]$Force,
    [string]$Manifest
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Manifest) { $Manifest = Join-Path $PSScriptRoot 'binaries.json' }
if (-not $Out) { $Out = Join-Path $RepoRoot 'app\src-tauri\resources' }
$Out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
$Cache = Join-Path $RepoRoot 'tools\bin\downloads'
New-Item -ItemType Directory -Force -Path $Cache, $Out | Out-Null
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]'Tls13'
} catch {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

function Get-Sha256([string]$Path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }

# What a stamp must match besides the version and hash: the files taken and the license texts.
function Get-Layout($Entry) {
    $parts = @($Entry.take) + @(@($Entry.licenses) | Where-Object { $_ } | ForEach-Object { "$($_.as)=$($_.sha256)" })
    return ($parts -join ';')
}

# Downloads one license text (cached, checked against its pinned SHA-256) and copies it to $Dest\<as>.
function Copy-License($Entry, $License, [string]$Dest) {
    $want = $License.sha256.ToLowerInvariant()
    $cacheDir = Join-Path (Join-Path $Cache 'licenses') $Entry.name
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $file = Join-Path $cacheDir ($License.as -replace '[\\/]', '_')
    if ((Test-Path -LiteralPath $file) -and (Get-Sha256 $file) -ne $want) { Remove-Item -LiteralPath $file -Force }
    if (-not (Test-Path -LiteralPath $file)) {
        Invoke-WebRequest -UseBasicParsing -Uri $License.url -OutFile "$file.part"
        Move-Item -LiteralPath "$file.part" -Destination $file -Force
    }
    $got = Get-Sha256 $file
    if ($got -ne $want) {
        Remove-Item -LiteralPath $file -Force
        throw "$($Entry.name): SHA-256 mismatch for $($License.url): expected $want, got $got (download deleted)"
    }
    $target = Join-Path $Dest ($License.as -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $file -Destination $target -Force
}

function Test-Take([string]$Name, [string[]]$Take) {
    foreach ($t in $Take) { if ($Name -like $t) { return $true } }
    return $false
}

# Copies the zip entries matching $Take (by full name, or by file name for a pattern without '/') flat into $Dest.
function Copy-FromZip([IO.Compression.ZipArchive]$Zip, [string[]]$Take, [string]$Dest) {
    $copied = 0
    foreach ($e in $Zip.Entries) {
        if (-not $e.Name) { continue }
        $hit = $false
        foreach ($t in $Take) {
            if ($t.Contains('/')) { if ($e.FullName -like $t) { $hit = $true } }
            elseif ($e.Name -like $t -and -not $e.FullName.Contains('/')) { $hit = $true }
        }
        if (-not $hit) { continue }
        $target = Join-Path $Dest $e.Name
        $in = $e.Open()
        try {
            $outStream = [IO.File]::Create($target)
            try { $in.CopyTo($outStream) } finally { $outStream.Dispose() }
        } finally { $in.Dispose() }
        $copied++
    }
    return $copied
}

$entries = @((Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json).tools)
if ($Only) { $entries = @($entries | Where-Object { $Only -contains $_.name }) }
if (-not $entries) { throw "nothing to fetch (-Only $($Only -join ','))" }

foreach ($b in $entries) {
    $want = $b.sha256.ToLowerInvariant()
    $dest = Join-Path $Out $b.dest
    $stampPath = Join-Path (Join-Path $Out '.stamps') "$($b.dest).json"
    $exe = Join-Path $dest "$($b.dest).exe"
    $layout = Get-Layout $b
    if (-not $Force -and (Test-Path -LiteralPath $stampPath) -and (Test-Path -LiteralPath $exe)) {
        $stamp = Get-Content -Raw -LiteralPath $stampPath | ConvertFrom-Json
        if ($stamp.sha256 -eq $want -and $stamp.version -eq $b.version -and $stamp.layout -eq $layout) {
            Write-Output "$($b.name) $($b.version): already in $dest"
            continue
        }
    }

    $file = Join-Path $Cache ($b.url.Split('/')[-1])
    if ((Test-Path -LiteralPath $file) -and (Get-Sha256 $file) -ne $want) {
        Write-Output "$($b.name): the cached $file doesn't match, downloading it again"
        Remove-Item -LiteralPath $file -Force
    }
    if (-not (Test-Path -LiteralPath $file)) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -UseBasicParsing -Uri $b.url -OutFile "$file.part"
        Move-Item -LiteralPath "$file.part" -Destination $file -Force
        Write-Output ("{0}: downloaded {1} ({2:N1} MB in {3:N0} s)" -f $b.name, $b.url, ((Get-Item -LiteralPath $file).Length / 1MB), $sw.Elapsed.TotalSeconds)
    }
    $got = Get-Sha256 $file
    if ($got -ne $want) {
        Remove-Item -LiteralPath $file -Force
        throw "$($b.name): SHA-256 mismatch for $($b.url): expected $want, got $got (download deleted)"
    }
    Write-Output "$($b.name): SHA-256 ok ($want)"

    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $take = @($b.take)
    if ($file -like '*.zip') {
        $zip = [IO.Compression.ZipFile]::OpenRead($file)
        try {
            if ($b.inner) {
                $innerEntry = $zip.GetEntry($b.inner)
                if (-not $innerEntry) { throw "$($b.name): $($b.inner) is not in $file" }
                $buffer = New-Object IO.MemoryStream
                $s = $innerEntry.Open()
                try { $s.CopyTo($buffer) } finally { $s.Dispose() }
                $buffer.Position = 0
                $innerZip = New-Object IO.Compression.ZipArchive($buffer)
                try { $n = Copy-FromZip $innerZip $take $dest } finally { $innerZip.Dispose(); $buffer.Dispose() }
            } else {
                $n = Copy-FromZip $zip $take $dest
            }
        } finally { $zip.Dispose() }
    } else {
        if (-not (Test-Take (Split-Path $file -Leaf) $take)) { throw "$($b.name): $file is not an archive and not in take" }
        Copy-Item -LiteralPath $file -Destination $dest
        $n = 1
    }
    if (-not (Test-Path -LiteralPath $exe)) { throw "$($b.name): $exe is missing after unpacking (check take)" }
    $licenses = @(@($b.licenses) | Where-Object { $_ })
    foreach ($l in $licenses) { Copy-License $b $l $dest }
    if ($licenses) { Write-Output "$($b.name): $($licenses.Count) license texts, SHA-256 ok" }

    $argList = (@($b.versionArgs) | ForEach-Object { $_ }) -join ' '
    $verOut = @(cmd /c "`"$exe`" $argList 2>&1")
    $first = "$($verOut | Select-Object -First 1)"
    if ($LASTEXITCODE -ne 0) { throw "$($b.name): $exe $argList exited with $LASTEXITCODE ($first)" }
    $mb = [math]::Round(((Get-ChildItem -LiteralPath $dest -File -Recurse | Measure-Object Length -Sum).Sum) / 1MB, 1)
    $stampJson = [ordered]@{ name = $b.name; version = $b.version; sha256 = $want; url = $b.url; layout = $layout } | ConvertTo-Json
    New-Item -ItemType Directory -Force -Path (Split-Path $stampPath -Parent) | Out-Null
    [IO.File]::WriteAllText($stampPath, $stampJson, (New-Object Text.UTF8Encoding($false)))
    Write-Output "$($b.name) $($b.version): $n files, $mb MB in $dest; $first"
}
