# Minimal protobuf wire-format reader for the repo indexes (index.pb). No schema: a message is decoded into a
# hashtable of field number -> list of raw values (varints as [UInt64], length-delimited fields as [byte[]]).
# Callers decode nested messages and strings themselves (Read-PbMessage on the bytes, Get-PbString).
# Field numbers of the two index schemas are documented in docs/phases/phase-1-backend-foundation.md section 10 and in
# server/core/src/main/kotlin/madoyomi/core/repo/AnimeNetworkExtensionStore.kt (Aniyomi) and upstream's
# NetworkExtensionStore.kt (Mihon).

function Expand-Gzip([byte[]]$Bytes) {
    if ($Bytes.Length -lt 2 -or $Bytes[0] -ne 0x1f -or $Bytes[1] -ne 0x8b) { return , $Bytes }
    $in = New-Object IO.MemoryStream(, $Bytes)
    $gz = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
    $out = New-Object IO.MemoryStream
    $gz.CopyTo($out)
    $gz.Dispose()
    return , $out.ToArray()
}

function Read-PbMessage([byte[]]$Bytes) {
    $fields = @{}
    $pos = 0
    $len = $Bytes.Length
    while ($pos -lt $len) {
        $key = [UInt64]0; $shift = 0
        do { $b = $Bytes[$pos]; $pos++; $key = $key -bor ([UInt64]($b -band 0x7f) -shl $shift); $shift += 7 } while ($b -band 0x80)
        $field = [int]($key -shr 3)
        $wire = [int]($key -band 7)
        switch ($wire) {
            0 {
                $v = [UInt64]0; $shift = 0
                do { $b = $Bytes[$pos]; $pos++; $v = $v -bor ([UInt64]($b -band 0x7f) -shl $shift); $shift += 7 } while ($b -band 0x80)
                $value = $v
            }
            1 { $value = [BitConverter]::ToUInt64($Bytes, $pos); $pos += 8 }
            2 {
                $n = [UInt64]0; $shift = 0
                do { $b = $Bytes[$pos]; $pos++; $n = $n -bor ([UInt64]($b -band 0x7f) -shl $shift); $shift += 7 } while ($b -band 0x80)
                $value = New-Object byte[] ([int]$n)
                [Array]::Copy($Bytes, $pos, $value, 0, [int]$n)
                $pos += [int]$n
            }
            5 { $value = [BitConverter]::ToUInt32($Bytes, $pos); $pos += 4 }
            default { throw "Unsupported protobuf wire type $wire at offset $pos" }
        }
        if (-not $fields.ContainsKey($field)) { $fields[$field] = New-Object System.Collections.ArrayList }
        [void]$fields[$field].Add($value)
    }
    return $fields
}

function Get-PbString($Fields, [int]$Number) {
    if (-not $Fields.ContainsKey($Number)) { return $null }
    return [Text.Encoding]::UTF8.GetString([byte[]]$Fields[$Number][0])
}

function Get-PbInt($Fields, [int]$Number, $Default = 0) {
    if (-not $Fields.ContainsKey($Number)) { return $Default }
    return [long]$Fields[$Number][0]
}

<#
  Reads a v2 extension-store index (index.pb, optionally gzipped) and returns one object per extension:
  Name, Pkg, ApkUrl, Lib, VersionCode, VersionName, ContentWarning (SAFE/MIXED/NSFW/UNSPECIFIED), IsTorrent, Langs.
  -Schema Aniyomi: field 8 is is_torrent and the sources are field 9; Mihon: the sources are field 8.
#>
function Read-ExtensionIndex([byte[]]$Bytes, [ValidateSet('Aniyomi', 'Mihon')][string]$Schema) {
    $warnings = @{ 0 = 'UNSPECIFIED'; 1 = 'SAFE'; 2 = 'MIXED'; 3 = 'NSFW' }
    $store = Read-PbMessage (Expand-Gzip $Bytes)
    if (-not $store.ContainsKey(101)) { throw 'index.pb has no inline extension list (field 101)' }
    $list = Read-PbMessage ([byte[]]$store[101][0])
    $sourceField = if ($Schema -eq 'Aniyomi') { 9 } else { 8 }
    foreach ($raw in $list[1]) {
        $e = Read-PbMessage ([byte[]]$raw)
        $res = Read-PbMessage ([byte[]]$e[3][0])
        $langs = @()
        if ($e.ContainsKey($sourceField)) {
            $langs = @($e[$sourceField] | ForEach-Object { Get-PbString (Read-PbMessage ([byte[]]$_)) 3 } | Sort-Object -Unique)
        }
        [pscustomobject]@{
            Name           = Get-PbString $e 1
            Pkg            = Get-PbString $e 2
            ApkUrl         = Get-PbString $res 1
            Lib            = Get-PbString $e 4
            VersionCode    = Get-PbInt $e 5
            VersionName    = Get-PbString $e 6
            ContentWarning = $warnings[[int](Get-PbInt $e 7)]
            IsTorrent      = ($Schema -eq 'Aniyomi') -and ((Get-PbInt $e 8) -ne 0)
            Langs          = $langs
        }
    }
}
