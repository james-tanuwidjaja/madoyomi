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
- [ ] `docs/research/suwayomi-notes.md` written (classloader, stubs, DB, API, WebView)

### S2 — Protobuf index
- [ ] `index.pb` decoded raw with `protoc --decode_raw`
- [ ] Field names confirmed against Mihon's client code
- [ ] `docs/research/index-pb.proto` written
- [ ] Kotlin parser prints all extensions; entry count is plausible

### S3 — Anime extension on JVM
- [ ] One yuzono APK chosen and downloaded to `tools/apks/`
- [ ] Converted with dex2jar
- [ ] Referenced classes listed in `docs/research/anime-ext-classes.md`
- [ ] `extensions-lib` version identified
- [ ] Harness loads the extension class
- [ ] `getPopularAnime(1)` returns results
- [ ] `getEpisodeList` returns episodes
- [ ] `getVideoList` returns videos with headers/subtitles
- [ ] Missing class/method log captured

### S4 — mpv playback
- [ ] mpv plays a returned video with its headers
- [ ] External subtitles load
- [ ] JSON IPC over a named pipe works (`time-pos` read)
- [ ] HLS and MP4 both tested

### S5 — Tauri sidecar
- [x] Tauri 2 + Vue 3 scaffold in `app/`
- [ ] Dummy JVM server prints its port and serves `/health`
- [ ] Tauri spawns it and parses the port
- [ ] Vue calls `/health` through the backend URL
- [ ] Sidecar is killed when the app closes

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
| Fork vs. library | _TBD_ | | |
| REST vs. GraphQL | _TBD_ | | |
| One JVM vs. isolated | _TBD_ | | |

## 10. Findings log

_Write notes here as spikes complete: what worked, what broke, surprises._
