# Phase 0 — Spikes

> Back to [PLAN.md](../PLAN.md) · Next: [Phase 1](phase-1-backend-foundation.md)

## 1. Goal

Prove that each hard part of Madoyomi works on its own before building the real system: Mihon manga extensions on the JVM, the protobuf index, Aniyomi anime extensions on the JVM, mpv playback with source headers, and a Tauri-managed JVM sidecar. Spike code is throwaway and lives under `spikes/` (or a scratch branch). What we keep are the **findings** written into this file and the decisions they unlock.

Timebox: ~1–2 weeks. If a spike goes over its timebox, stop, write down what blocked it, and decide whether to change approach.

## 2. Depends on / inputs

- The Tauri 2 + Vue 3 scaffold in `app/` (done, commit `547f422`).
- Tools in `tools/bin/` (gitignored): JDK 17+ (Temurin), `dex2jar`, `jadx` (to inspect classes), `mpv.exe`, `protoc`.
- Repos: Keiyoushi `index.pb` (manga) and yuzono `index.min.json` (anime). URLs are in [PLAN.md](../PLAN.md).
- Upstream sources to read: Suwayomi-Server, Mihon (`ExtensionApi` / repo index parsing), Aniyomi `extensions-lib`.

## 3. Deliverables

- `docs/research/suwayomi-notes.md`: how Suwayomi loads extensions (classloader, stubs, DB, API) and which modules we can reuse.
- `docs/research/index-pb.proto`: the reverse-engineered `index.pb` schema, plus a small parser that prints every extension.
- `docs/research/anime-ext-classes.md`: the `extensions-lib` / Android classes one yuzono APK references.
- `spikes/anime-harness/`: Kotlin harness that loads one anime extension and calls popular → episodes → videos.
- `spikes/mpv-play/`: a script or small program that plays one returned `Video` with its headers/subs.
- `spikes/sidecar/`: a dummy JVM jar plus Tauri wiring that spawns it and reads its port.
- A **decision record** (section 9 below) for fork vs. library and REST vs. GraphQL.

## 4. Detailed steps

### S1 — Manga extensions on the JVM (Suwayomi)
1. Clone Suwayomi-Server and build/run it locally (`./gradlew :server:run` or the release jar).
2. Add the Keiyoushi repo in its WebUI/settings. Note whether it reads `index.pb` natively or needs `index.min.json`.
3. Install 2–3 extensions (one simple HTML source, one API/JSON source, one with Cloudflare if possible).
4. Browse popular, open a manga, load the chapter list, read pages.
5. Note: where the extension classloader lives, how Android stubs are packaged, DB tech (Exposed/H2/SQLite?), API style (GraphQL + REST), and how WebView/Cloudflare is handled.

### S2 — Protobuf index (`index.pb`)
1. Download `index.pb` and run `protoc --decode_raw < index.pb` to see the field numbers and nesting.
2. Cross-check with Mihon's client code that parses the repo index (find the `.proto` or `@ProtoNumber` model classes).
3. Write `index-pb.proto` with named fields: name, pkg, apk, lang, code, version, nsfw, sources (id, name, lang, baseUrl).
4. Write a small Kotlin parser (`kotlinx.serialization.protobuf`) that prints all entries; compare counts with the JSON index if one exists.

### S3 — Anime extension on the JVM
1. Pick one simple yuzono extension (plain HTTP source, no WebView, no Cloudflare).
2. Download the APK into `tools/apks/`, then run `dex2jar` on it to get a `.jar`.
3. Inspect with `jadx` and list every referenced `eu.kanade.tachiyomi.animesource.*`, `eu.kanade.tachiyomi.network.*`, `android.*` and `androidx.*` class. Record the `extensions-lib` version (manifest metadata / APK name `v14.x`).
4. Create `spikes/anime-harness/` (Gradle, Kotlin/JVM). Add a compile-only `extensions-lib` stub with implementations, OkHttp, Jsoup, kotlinx.serialization, and the minimal Android stubs (reuse Suwayomi's `AndroidCompat` where possible).
5. Load the jar with a `URLClassLoader`, then instantiate the class named in the manifest (`tachiyomi.animeextension.class` meta-data / `AnimeSourceFactory`).
6. Call `getPopularAnime(1)` → `getEpisodeList(anime)` → `getVideoList(episode)` and print the results, including `Video.headers`, subtitle and audio tracks.
7. Log every `ClassNotFoundException` / `NoSuchMethodError`: this list is the start of the Phase 2 stub backlog.

### S4 — Playback with mpv
1. Take a `Video` from S3 (URL + headers + subtitle tracks).
2. Build the mpv command line: `--http-header-fields="Referer: …","User-Agent: …"`, `--sub-files=…`, `--audio-files=…`, `--force-media-title=…`.
3. Start mpv with `--input-ipc-server=\\.\pipe\madoyomi-mpv-test` and confirm that `get_property time-pos` works over the named pipe.
4. Test at least one HLS (`.m3u8`) and one MP4 source. Write down which header formats work.

### S5 — Tauri sidecar
1. ~~Scaffold Tauri 2 + Vue 3 in `app/`~~ (done in `547f422`).
2. Create a dummy JVM server (Javalin or Ktor) that binds `127.0.0.1:0`, prints `MADOYOMI_PORT=<port>` to stdout, and serves `GET /health`.
3. In `app/src-tauri/src/lib.rs`, spawn `java -jar dummy.jar` (later the bundled JRE) using `tauri-plugin-shell` or `std::process::Command`, read stdout until the port line appears, and store the port in app state.
4. Expose a Tauri command `get_backend_url()`. Have Vue call it and then `fetch('/health')`.
5. Kill the sidecar when the app exits, including on crash (job object on Windows).

## 5. Checklist

### S1 — Manga via Suwayomi
- [ ] Suwayomi-Server builds and runs locally
- [ ] Keiyoushi repo added; note whether it uses `index.pb` or JSON
- [ ] 2–3 manga extensions installed
- [ ] Browse → details → chapters → pages works for each
- [x] `docs/research/suwayomi-notes.md` written (classloader, stubs, DB, API, WebView)

### S2 — Protobuf index
- [x] `index.pb` decoded raw with `protoc --decode_raw`
- [x] Field names confirmed against Mihon's client code
- [x] `docs/research/index-pb.proto` written
- [x] Kotlin parser prints all extensions; entry count is plausible

### S3 — Anime extension on JVM
- [x] One yuzono APK chosen and downloaded to `tools/apks/`
- [x] Converted with dex2jar
- [x] Referenced classes listed in `docs/research/anime-ext-classes.md`
- [x] `extensions-lib` version identified
- [ ] Harness loads the extension class
- [ ] `getPopularAnime(1)` returns results
- [ ] `getEpisodeList` returns episodes
- [ ] `getVideoList` returns videos with headers/subtitles
- [ ] Missing class/method log captured

### S4 — mpv playback
- [ ] mpv plays a returned video with its headers
- [x] External subtitles load
- [x] JSON IPC over a named pipe works (`time-pos` read)
- [x] HLS and MP4 both tested

### S5 — Tauri sidecar
- [x] Tauri 2 + Vue 3 scaffold in `app/`
- [x] Dummy JVM server prints its port and serves `/health`
- [x] Tauri spawns it and parses the port
- [x] Vue calls `/health` through the backend URL
- [x] Sidecar is killed when the app closes

### Wrap-up
- [ ] Decision record filled in (section 9)
- [ ] PLAN.md §7 open decisions updated

## 6. Exit criteria

- [ ] All five spikes pass, or have a written workaround.
- [ ] One anime episode played end-to-end: extension → `Video` → mpv.
- [ ] Fork-vs-library and REST-vs-GraphQL decisions recorded.

## 7. Risks & notes

- Anime `extensions-lib` may differ a lot from the manga lib (see PLAN.md §6, "extensions-lib version changes"). If S3 hits many missing APIs, budget extra time in Phase 2.
- `index.pb` may change without notice. Keep the parser tolerant (unknown fields ignored).
- Do not commit APKs or binaries: `tools/apks/`, `*.apk` and `*.dex` are gitignored.

## 8. Decisions closed here

- Fork Suwayomi-Server, or write a fresh backend reusing its extension-host code? (PLAN.md §7)
- REST, or extend Suwayomi's GraphQL? (PLAN.md §7)
- One JVM process for both extension types, or isolated processes? (initial answer; can be revisited in Phase 1)

## 9. Decision record

| Decision | Choice | Reason | Date |
|---|---|---|---|
| Fork vs. library | _Proposed:_ **fork** | Suwayomi is a single app, not a library: one `server` module plus `AndroidCompat`. Everything we need (dex2jar-in-process + `BytecodeEditor`, `ChildFirstURLClassLoader`, `AndroidCompat`, the shared `eu.kanade.tachiyomi.network`, the `index.pb`/JSON store service, Exposed/H2) lives inside it. It once had anime tables (M0004–M0006, dropped in M0019), so the structure fits. **Confirm after the S1 build/run.** | 2026-11-03 (proposed) |
| REST vs. GraphQL | _Proposed:_ **extend GraphQL** | Suwayomi's REST v1 now only serves binaries (icons, thumbnails, pages, downloads). Everything else is GraphQL (~14.5k lines incl. subscriptions). Building a parallel REST API would mean rewriting most of that. Keep REST for binary endpoints (image proxy, video/page bytes). The PLAN.md §5 sketch then maps to GraphQL queries and mutations. **Confirm with the user.** | 2026-11-03 (proposed) |
| One JVM vs. isolated | _Proposed (initial):_ **one JVM** | Anime and manga extensions use the same `eu.kanade.tachiyomi.network` API (`GET`/`POST`/`await*`, `NetworkHelper`), the same Injekt DI and the same Android stubs. Their source APIs live in different packages (`source` vs `animesource`), so nothing clashes. Each extension already gets its own classloader. Revisit in Phase 1 for crash isolation. | 2026-11-03 (proposed) |

## 10. Findings log

### 2026-11-03

**Tooling.** The system `java` is a Zulu 11 JRE, too old (Suwayomi needs JDK 21). Temurin 21.0.13, jadx 1.5.6 and Gradle 9.8.2 are in `tools/bin/`. Upstream sources (Suwayomi-Server @ `7b061be`, aniyomi @ `9741444`, mihon @ `e588e04`, aniyomiorg/extensions-lib tags 14/16/v17) are cloned into `tools/upstream/` (gitignored). `d2j-dex2jar.sh` breaks under Git Bash because of its `:`-separated classpath: call java directly (see `spikes/README.md`).

**Blocked: running third-party code.** The agent's permission policy refused `./gradlew :server:shadowJar` in the Suwayomi clone ("code from external"). The same applies to running the Suwayomi jar and executing extension code in the S3 harness. S1 (run) and S3 (runtime) are waiting on the user's go-ahead. Everything else was done by reading source, static analysis, or our own code.

**S1 (from source, run pending).** See [suwayomi-notes.md](../research/suwayomi-notes.md). Highlights: Suwayomi parses Keiyoushi's `index.pb` natively (gzip-sniffing, protobuf/JSON by first byte). It uses the Keiyoushi `jarUrl` to skip dex2jar. GraphQL is the main API and REST only serves binaries. Cloudflare is handled through FlareSolverr, and WebView through KCEF (JCEF).

**S2 (done).** `index.pb` is **gzip-compressed** protobuf served as `application/octet-stream` with no `Content-Encoding`, so clients must gunzip it themselves. The schema matches Mihon's `NetworkExtensionStore` exactly, plus the Keiyoushi field 501 `jarUrl`: [index-pb.proto](../research/index-pb.proto). The Kotlin parser (`spikes/index-pb`) decodes 1448 extensions / 2361 sources (all unique ids), which matches the raw decode. All entries are lib 1.6 and all have a `jarUrl`. Content warnings: 564 safe, 490 mixed, 394 NSFW. The Keiyoushi `index.min.json` is now just an "Outdated App" stub.

**S3 (static done, runtime pending).** See [anime-ext-classes.md](../research/anime-ext-classes.md). AnimeGG ("14.2") and AnimePahe ("16.56") were dex2jar'd cleanly. **The manifest `extensionLib` (14/16) does not describe the binary API**: the lib-14 AnimeGG calls lib-16 `Hoster`/`Video` API. So the host must implement the newest `animesource` surface (port the Aniyomi app's `source-api`, which keeps the deprecated `Video` constructor). The extensions are R8-minified and bundle `keiyoushi.utils`. AnimePahe uses `android.webkit.WebView`, so it needs a real WebView (KCEF), not a stub. Lib v17 (tagged upstream, not used by yuzono yet: 197 × 14.x, 50 × 16.x) moves more methods into `AnimeSource` (episode/season updates, related anime). Expect to have to follow it.

**S4 (mechanics done; real-extension video pending S3).** `spikes/mpv-play/play.ps1` plus a local header-checking proxy (`header-proxy.mjs`) that returns 403 without the right Referer/User-Agent. Results:
- MP4 (range requests) and HLS (master + 5 variant playlists + every `.ts` segment) both play with `time-pos` advancing over `\\.\pipe\madoyomi-mpv-test`. The headers reached **every** request, including HLS segments and the HTTP subtitle.
- Header formats that work: `--referrer=` and `--user-agent=` for those two, and **one `--http-header-fields-append="Name: value"` per other header**, which keeps values containing `,`/`;` intact (`X-Test: a,b;c` arrived verbatim). Avoid the comma-joined `--http-header-fields=` form.
- Subtitles: `--sub-files-append=` per file. Both HTTP URLs and local paths load as external tracks (checked via the `track-list` property).
- **Use `--ytdl=no`.** Without it, mpv's ytdl hook retries a failed URL through yt-dlp with a browser User-Agent, which hides header bugs and leaks requests.
- mpv probes every HLS variant at start-up (a request per variant playlist plus segments), so expect a burst of requests against rate-limited hosts.
- Still to do: `--audio-files-append` with a real multi-audio source. Lib-16 `Video.mpvArgs`/`timestamps` should feed the Phase 4 launcher.

**S5 (done).** `spikes/sidecar` (Javalin 7 on `127.0.0.1:0`) prints `MADOYOMI_PORT=<port>`, serves `/health` with CORS for `http://localhost:1420` / `tauri.localhost`, starts in ~1 s on JDK 21, and exits when its stdin closes. `app/src-tauri/src/sidecar.rs` spawns it (`CREATE_NO_WINDOW`), parses the port on a reader thread (Mutex + Condvar), and exposes `get_backend_url` (waits up to 20 s; uses `spawn_blocking`). Verified with `pnpm tauri dev`: the log shows `backend listening on 127.0.0.1:<port>` and then `request GET /health -> 200 (origin=http://localhost:1420)` from the Vue home screen. A window close (WM_CLOSE) logs `stopping backend` and the JVM is gone. **Force-killing `madoyomi.exe` also takes the JVM down** (Job Object with `KILL_ON_JOB_CLOSE`). Known gap: there is a tiny window between `spawn` and `AssignProcessToJobObject`. Phase 3 can close it with `CREATE_SUSPENDED` or by spawning through a job-aware wrapper.
