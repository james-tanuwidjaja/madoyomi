<#
.SYNOPSIS
  Builds the minimal Java runtime that release builds bundle (Phase 7 Stage 1): the modules the server jar needs
  (jdeps) plus the ones extensions load at runtime, linked with jlink into app\src-tauri\resources\jre (gitignored).

.DESCRIPTION
  1. `jdeps --print-module-deps` on the server jar (the newest server\api\build\Madoyomi-Server-*.jar unless -Jar).
  2. Adds the modules in $RuntimeModules below: extensions are separate jars loaded at runtime (dex2jar output), so
     jdeps can't see what they use, and some modules are only reached by reflection or ServiceLoader. Each has its
     reason; add one there (with the reason) when the compat harness or the live spec fails on the jlink runtime.
     Drops the modules in $NotShipped: jdeps finds them in library code Madoyomi never runs (a debugger agent,
     Kerberos logins for PostgreSQL), and they would put the runtime over the size target.
  3. `jlink --strip-debug --no-header-files --no-man-pages --compress=zip-6` from the JDK's jmods into -Out (the old
     runtime is removed first; lib\jvm.lib, a static link library, is left out), then checks that `java -version` runs and prints the size and module count.

  4. tools\lib\JreProbe.java, compiled with the JDK's javac against the jar, runs on the new runtime: JavaScript
     through the QuickJs and Duktape stubs, dates in other locales, legacy charsets, zipfs, TLS providers, headless
     imaging, preferences, JDBC and JNDI. A failed check fails the script (-NoProbe skips it).

  The JDK is tools\bin\jdk-21 (a full JDK with jmods; see README "Prerequisites"). The result is a build output:
  never commit it. tools\build-release.ps1 runs this script; the shell finds the runtime at <resources>\jre.
  Keep this file ASCII; it runs on Windows PowerShell 5.1 and PowerShell 7.

.PARAMETER Jar
  The server jar to analyse. Default: the newest server\api\build\Madoyomi-Server-*.jar (build it first with
  .\gradlew.bat :api:shadowJar in server\, or run tools\build-release.ps1).

.PARAMETER Out
  Where the runtime goes. Default: app\src-tauri\resources\jre.

.PARAMETER MaxSizeMB
  Fails when the runtime is larger (Phase 7 step 1's target: 60 MB). 0 turns the check off.

.EXAMPLE
  .\tools\jlink.ps1
.EXAMPLE
  .\tools\jlink.ps1 -Out tools\bin\jre-test -ExtraModules jdk.crypto.mscapi
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [string]$Jdk,
    [string]$Out,
    [string[]]$ExtraModules = @(),
    [int]$MaxSizeMB = 60,
    [switch]$NoProbe
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Jdk) { $Jdk = Join-Path $RepoRoot 'tools\bin\jdk-21' }
if (-not $Out) { $Out = Join-Path $RepoRoot 'app\src-tauri\resources\jre' }
$Out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
$jdeps = Join-Path $Jdk 'bin\jdeps.exe'
$jlink = Join-Path $Jdk 'bin\jlink.exe'
$jmods = Join-Path $Jdk 'jmods'
foreach ($p in $jdeps, $jlink, $jmods) {
    if (-not (Test-Path -LiteralPath $p)) { throw "not a full JDK (missing $p): tools\bin\jdk-21 must be a JDK 21 with jmods" }
}
if (-not $Jar) {
    $newest = Get-ChildItem (Join-Path $RepoRoot 'server\api\build') -Filter 'Madoyomi-Server-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { throw 'no server jar in server\api\build: run .\gradlew.bat :api:shadowJar in server\ first' }
    $Jar = $newest.FullName
}
if (-not (Test-Path -LiteralPath $Jar)) { throw "server jar not found: $Jar" }

# Modules jdeps can't find in the server jar. Keep each reason.
$RuntimeModules = [ordered]@{
    'jdk.crypto.ec'     = 'the SunEC provider: TLS with ECDHE/ECDSA (most sites), which JDK 21 keeps outside java.base'
    'jdk.localedata'    = 'locales other than English: extensions parse dates with SimpleDateFormat(..., Locale("es")) and the like'
    'jdk.charsets'      = 'legacy charsets beyond java.base (Shift_JIS, EUC-KR, GBK, Big5, windows-125x) for sites and archive names'
    'java.net.http'     = 'java.net.http.HttpClient, which an extension may use instead of OkHttp'
    'java.scripting'    = 'javax.script (ScriptEngineManager), looked up by extensions that evaluate a site''s JavaScript'
    'jdk.zipfs'         = 'the zip FileSystemProvider (FileSystems.newFileSystem on an archive), found through ServiceLoader'
    'jdk.unsupported'   = 'sun.misc.Unsafe and sun.reflect, used by Kotlin, OkHttp, Gson and Truffle through reflection'
    'jdk.management'    = 'the com.sun.management MXBeans (process and memory figures), looked up at runtime'
}

# Modules jdeps lists that the runtime leaves out: code paths of bundled libraries that Madoyomi never takes.
# Keep each reason; move a module back to the runtime (and out of here) if a check proves it is reached.
$NotShipped = [ordered]@{
    'jdk.jdi'            = 'the debugger interface: javassist''s HotSwapper only (a development tool)'
    'jdk.attach'         = 'attaching to a JVM: javassist''s HotSwapAgent only; jcmd and jstat attach from the JDK side'
    'java.instrument'    = 'Java agents: the coroutines debug agent, Jetty''s and H2''s optional agent hooks, javassist'
    'java.security.jgss' = 'Kerberos/GSS-API: PostgreSQL''s GSS login and Jetty''s SPNEGO; the server uses H2 and no SPNEGO'
    'java.compiler'      = 'javax.tools/javax.lang.model: H2''s Java source functions and jte''s template compiler, neither used'
    'jdk.jfr'            = 'Flight Recorder: Truffle''s JFR events, which only its optimizing (JVMCI) runtime emits; GraalJS runs on the fallback runtime here'
}

Write-Output "jar: $Jar"
Write-Output "jdk: $Jdk"
$sw = [Diagnostics.Stopwatch]::StartNew()
$depsOut = cmd /c "`"$jdeps`" --ignore-missing-deps --multi-release 21 --print-module-deps `"$Jar`" 2>&1"
if ($LASTEXITCODE -ne 0) { $depsOut | Select-Object -Last 20 | ForEach-Object { Write-Output $_ }; throw "jdeps failed ($LASTEXITCODE)" }
$line = ($depsOut | Where-Object { $_ -match '^[a-z][\w.]*(,[a-z][\w.]*)*$' } | Select-Object -Last 1)
if (-not $line) { $depsOut | Select-Object -Last 20 | ForEach-Object { Write-Output $_ }; throw 'jdeps printed no module list' }
$fromJdeps = @($line.Trim().Split(',') | Where-Object { $_ })
Write-Output ("jdeps ({0:N0} s): {1}" -f $sw.Elapsed.TotalSeconds, ($fromJdeps -join ','))

$modules = New-Object System.Collections.Generic.List[string]
foreach ($m in $fromJdeps + @($RuntimeModules.Keys) + $ExtraModules) {
    if ($m -and -not $modules.Contains($m) -and ($NotShipped.Keys -notcontains $m -or $ExtraModules -contains $m)) { $modules.Add($m) }
}
foreach ($m in $NotShipped.Keys) { if ($fromJdeps -contains $m -and -not $modules.Contains($m)) { Write-Output "  - $m : $($NotShipped[$m])" } }
foreach ($m in $RuntimeModules.Keys) { if ($fromJdeps -notcontains $m) { Write-Output "  + $m : $($RuntimeModules[$m])" } }
foreach ($m in $ExtraModules) { Write-Output "  + $m : -ExtraModules" }

if (Test-Path -LiteralPath $Out) { Remove-Item -LiteralPath $Out -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $Out -Parent) | Out-Null
$sw.Restart()
$linkOut = cmd /c "`"$jlink`" --module-path `"$jmods`" --add-modules $($modules -join ',') --strip-debug --no-header-files --no-man-pages --compress=zip-6 --exclude-files=**/jvm.lib --output `"$Out`" 2>&1"
if ($LASTEXITCODE -ne 0) { $linkOut | ForEach-Object { Write-Output $_ }; throw "jlink failed ($LASTEXITCODE)" }

$java = Join-Path $Out 'bin\java.exe'
$versionOut = @(cmd /c "`"$java`" -version 2>&1")
$version = "$($versionOut | Select-Object -First 1)"
if ($LASTEXITCODE -ne 0) { throw "the new runtime doesn't start: $version" }
$bytes = (Get-ChildItem -LiteralPath $Out -Recurse -File | Measure-Object Length -Sum).Sum
$mb = [math]::Round($bytes / 1MB, 1)
$count = @(cmd /c "`"$java`" --list-modules 2>&1").Count
Write-Output ("runtime: {0} ({1} modules, {2} MB, linked in {3:N0} s): {4}" -f $Out, $count, $mb, $sw.Elapsed.TotalSeconds, $version)
if (-not $NoProbe) {
    $probeDir = Join-Path $RepoRoot 'tools\bin\jreprobe'
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    $javac = Join-Path $Jdk 'bin\javac.exe'
    $src = Join-Path $PSScriptRoot 'lib\JreProbe.java'
    $c = @(cmd /c "`"$javac`" -nowarn -d `"$probeDir`" -cp `"$Jar`" `"$src`" 2>&1")
    if ($LASTEXITCODE -ne 0) { $c | ForEach-Object { Write-Output $_ }; throw "javac failed on $src ($LASTEXITCODE)" }
    $probe = @(cmd /c "`"$java`" -cp `"$Jar;$probeDir`" JreProbe 2>&1")
    $probeExit = $LASTEXITCODE
    $probe | Where-Object { $_ -match '^(OK|FAIL|PROBE)' } | ForEach-Object { Write-Output "  $_" }
    if ($probeExit -ne 0) { $probe | Select-Object -Last 30 | ForEach-Object { Write-Output "  | $_" }; throw "JreProbe failed on the new runtime ($probeExit)" }
}
if ($MaxSizeMB -gt 0 -and $mb -gt $MaxSizeMB) { throw "the runtime is $mb MB, over the $MaxSizeMB MB target" }
