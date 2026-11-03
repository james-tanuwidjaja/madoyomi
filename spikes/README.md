# Phase 0 spikes

Throwaway code for [docs/phases/phase-0-spikes.md](../docs/phases/phase-0-spikes.md). The findings live in `docs/research/` and in the phase doc. Nothing here is a dependency of the real app, except that `app/src-tauri` uses `sidecar/build/libs/sidecar-all.jar` in debug builds until the real backend exists.

Tools are expected in `tools/bin/` (gitignored): `jdk-21/` (Temurin 21), `jadx/`, `dex2jar/dex-tools-2.4.38/`, `gradle/`. mpv, protoc and node are expected on PATH. Generated output goes to each spike's `out/` folder (gitignored).

All Gradle commands run from `spikes/`, with `JAVA_HOME` pointing at `tools/bin/jdk-21`:

```powershell
$env:JAVA_HOME = "$PWD\..\tools\bin\jdk-21"
```

## S2: `index-pb/` (Mihon/Keiyoushi `index.pb` parser)

```powershell
# raw decode (the file is gzip-compressed protobuf)
curl.exe -sSL -o index-pb\out\index.pb https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb
# gunzip it first, e.g. in Git Bash: gunzip -c out/index.pb > out/index.bin; protoc --decode_raw < out/index.bin
.\gradlew :index-pb:run --args="out/index.pb"   # a path relative to index-pb/, or a URL; no args = the Keiyoushi URL
```

Schema: [docs/research/index-pb.proto](../docs/research/index-pb.proto).

## S3: anime extension analysis (`anime-harness/`)

Static part (done):

```bash
# from tools/apks/ (Git Bash)
curl -sSLO https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-en.animegg-v14.2.apk
../bin/jdk-21/bin/java -cp "../bin/dex2jar/dex-tools-2.4.38/lib/*" com.googlecode.dex2jar.tools.Dex2jarCmd -f -o x.jar x.apk
../bin/jdk-21/bin/jdeps --ignore-missing-deps --multi-release 21 -verbose:class x.jar
cmd //c "..\\bin\\jadx\\bin\\jadx.bat -q --no-src -d jadx-x x.apk"   # manifest meta-data
```

(`d2j-dex2jar.sh` does not work from Git Bash on Windows because it builds a `:`-separated classpath. Call java directly as shown above, or use the `.bat`.)

Runtime harness: not written yet, waiting on the decision about running third-party code (see the phase doc).

## S4: `mpv-play/`

```powershell
node mpv-play\header-proxy.mjs 18080          # in another terminal: a local proxy that 403s without the headers
.\mpv-play\play.ps1 -Url http://127.0.0.1:18080/mp4 -Header 'Referer: https://madoyomi.test/','User-Agent: MadoyomiSpike/1.0' -Sub http://127.0.0.1:18080/subs.srt
.\mpv-play\play.ps1 -Url http://127.0.0.1:18080/hls/x36xhzz.m3u8 -Header 'Referer: https://madoyomi.test/','User-Agent: MadoyomiSpike/1.0'
.\mpv-play\play.ps1 -VideoJson anime-harness\out\video.json   # once S3 produces one
```

Add `-Hidden` to skip the window/audio output, or `-Keep` to leave mpv running.

## S5: `sidecar/`

```powershell
.\gradlew :sidecar:shadowJar     # -> sidecar/build/libs/sidecar-all.jar
cd ..\app; pnpm tauri dev        # the shell spawns it; the home screen shows "Backend: ok (...)"
```

Overrides: `MADOYOMI_JAVA` (java executable) and `MADOYOMI_SIDECAR_JAR` (jar path).
