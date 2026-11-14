# Madoyomi

A Windows 11 desktop app for anime and manga that runs existing **Aniyomi** and **Mihon** extensions unchanged.

The name comes from 窓 *mado* ("window") + 読み *yomi* ("reading").

> [!WARNING]
> **Early development. Not usable yet:** episodes play in mpv and resume where you stopped, and the next episode starts by itself. **Chapters open in a reader**, one page or two side by side (right to left by default), or as one long webtoon strip, with zoom, that saves your page, resumes there and goes on into the next chapter. Each manga remembers its own mode and direction, and webtoons open as a strip by themselves (by genre, or a webtoon site); the fit, background, strip width and preloading are settings. Phase 0 (spikes), **Phase 1 (backend foundation)**, **Phase 2 (anime sources)**, **Phase 3 (frontend MVP)**, **Phase 4 (playback in mpv)** and **Phase 5 (the manga reader)** are done; **Phase 6 (library features: categories, updates, history, downloads, backups) is next**. Phase 5's exit run read a 103-page webtoon chapter from a fresh data dir with no stutter and no blank page, kept the memory flat over 5 chapters, and checked every mode, each manga's settings and resuming in every mode, after an app restart too ([phase-5 §10](docs/phases/phase-5-manga-reader.md#10-findings-log)). An episode's Play button opens a video picker (each server's qualities, the source's pick, and your last choice for that source preselected), with "Resume from mm:ss" or "Start over" when you stopped partway. The video plays in mpv's own window, with the source's headers, subtitles and audio tracks (named by their language). The backend runs mpv, one episode at a time, and mpv closes with the app. While it plays, the episode's row shows the time left, and it shows as Watched the moment it passes 85 %. Episodes can be marked watched or unwatched in bulk. When an episode ends, a toast counts down 10 s and plays the next one with your last choice of video (auto-play can be switched off in Settings). A video whose link expired is fetched again once, and if it still won't play, the picker reopens with the next video selected. **Settings → Player** sets when an episode counts as watched (50–95 %), a preferred quality and server (the picker's videos are ordered by them), the subtitle and audio language mpv starts with, and which mpv to run; each change is used from the next play. Phase 4's exit run checked it all from a fresh data dir, with MP4, HLS, DASH and MKV videos, ASS and WebVTT subtitles, several audio tracks, and links that expire before or while they play ([phase-4 §10](docs/phases/phase-4-playback.md#10-findings-log)).
>
> **The desktop app** starts the real backend itself, with a fresh API token and its data dir (`%APPDATA%\Madoyomi`). It shows a "starting" splash until the backend is healthy, and restarts the backend on its own if it crashes, with an overlay that says so. If the backend can't start at all, it shows why, with Retry and Open logs. The UI talks to the backend through typed GraphQL and live WebSocket events, and both carry on by themselves after a restart.
>
> **What you can do in the app, for anime and manga:**
> - **Repos and extensions:** add a repo by its URL (the app works out whether it's anime or manga), remove it, and refresh it. Browse what your repos offer, with search, a language filter and a "Show 18+" switch. Install, update and uninstall extensions and watch each step. The trust prompt shows the signing certificate and a warning for an extension whose certificate you haven't trusted yet.
> - **Browse your sources:** they're listed by language, with your pins at the top. A source shows its Popular, Latest and Search results as an endless grid of covers. Its filters (selects, sorts, checkboxes, include/exclude genres, text, groups) open in a side sheet, with Apply and Reset.
> - **Details and the Library:** a cover opens its details, refreshed from the source the first time, with its episodes or chapters (even 1000+ scroll smoothly), sorted and filtered. "Add to library" puts it in the Library, a grid sorted by title or date added.
> - **Play an episode** (new in Phase 4): Play on an episode row opens the video picker: choose a server and quality (your last choice for that source is preselected, so Enter plays it), and resume where you stopped or start over. It plays in mpv; you need mpv installed and on PATH, or its path set in Settings → Player (or `MADOYOMI_MPV`). The playing row shows the time left, an episode watched past 85 % shows as Watched at once, and a row's checkbox (Shift+click for a range) lets you mark episodes watched or unwatched, or every earlier one as watched. When an episode ends, "Next: Episode N" counts down 10 s and plays it (Play now, Cancel; Settings → Player turns auto-play off). If mpv can't open a video, the backend fetches the videos again once; if it still fails, the picker reopens with the error and the next video selected. Settings → Player also has the watched threshold, a preferred quality and server (they go first in the picker, marked Preferred, and are preselected for a source you haven't chosen on yet), the subtitle and audio language (or no subtitles), and the mpv path with a Check.
> - **Read a chapter** (new in Phase 5): click a chapter row (or its Read / Continue button), or "Start reading" / "Continue: …" on the manga's page. The reader takes the whole window and shows one page at a time: click the left or right third of the page, use the arrow keys (they follow the direction), Space, Page Up/Down, Home/End or the mouse wheel; the middle of the page (or M) shows the toolbar with the page slider, the previous and next chapter, the mode, the direction, Settings and fullscreen (F; Esc leaves it, then the reader). After the last page, "Next chapter: Chapter N" and one more step goes on into it at once, without leaving the reader (its pages are fetched ahead); before the first, the previous chapter. Your page is saved as you read, the chapter is marked Read on its last page, and it reopens where you stopped, also after a restart (a row shows "Page 13 of 92"). A page that fails shows why, with the site's HTTP status, and a click tries again. In the chapter list, the Read/Unread filter works and chapters can be marked read or unread in bulk (unread sends them back to page 1), or every earlier one as read.
>   **The webtoon mode** (the toolbar's mode switch, keeping your page): the chapter as one strip, at most 800 px wide, that scrolls with the wheel, the scrollbar, ↓/↑ (a little), Space and Page Up/Down (a screen) and Home/End; scrolling past its end opens the next chapter, past its top the previous one. "Page gap" puts a small gap between pages. Only the pages near what you see are loaded, so a 100+ page chapter scrolls smoothly and memory stays flat, and very tall images show whole. The page at the top of the window is the one saved, and reaching the end marks the chapter read; a further wheel notch at the very bottom (top) goes on into the next (previous) chapter.
>   **The double-page mode** (new in Stage 4; the toolbar's Single / Double / Webtoon): two pages side by side, the first one on the right when reading right to left. Page 1 is alone, and so is a wide page (a spread in one image); "Shift" pairs from page 1 instead. The slider shows "4–5 / 92", and the later page of the two is the one saved. **Zoom** (single and double): Ctrl+wheel about the pointer, Ctrl+= / Ctrl+− / Ctrl+0, or a double-click in the middle of the page; zoomed in, drag, the wheel or the arrows move around, and a page turn, Esc or another double-click goes back to fit. A mouse click on a toolbar button leaves Space and the arrows to the reader.
>
>   **Reader settings** (new in Stage 5): the toolbar's mode and direction are **this manga's own** and stay with it (also after a restart, and in backups). A manga with none opens in Webtoon when its genres or tags say Long Strip, Webtoon, Manhwa or Manhua, or when it comes from a webtoon site (Webtoons.com), else in your default (Single, right to left, to start with). The toolbar's **Settings** opens a sheet: "This manga" (its mode and direction, each with "Default", and Reset) and "All manga": the default mode and direction, the **page fit** (fit screen, fit width, fit height, original size; a page bigger than the window scrolls with the wheel, the keys or a drag before the next one), the **strip width** (600–1200 px or the window's), the page gap, the **background** (dark, black, gray, white) and **how many pages to preload** (2–12). The same global settings are in Settings → Reader, with "Reset to defaults".
> - **Settings:** the theme (System, Dark or Light), the player settings (above), **Reader** (new in Phase 5: the reader's settings on this device, the same as the reader's "All manga" with a reset (Stage 5), and the page cache's size, 1 GB by default, what it holds now and Clear), the backend's version and folders with Open logs, and each source's own settings (quality, mirror, title language, content filters, …), saved as you change them and used from the source's next page.
>
> All of it was checked from a fresh data dir in the Phase 3 exit run, including a backend crash in the middle ([phase-3 §10](docs/phases/phase-3-frontend-mvp.md#10-findings-log)), and playback in Phase 4's ([phase-4 §10](docs/phases/phase-4-playback.md#10-findings-log)).
>
> The backend in `server/` is a Suwayomi-Server fork, rebranded as Madoyomi-Server. It runs as a local sidecar (loopback only, random port, API token). From its GraphQL API you can:
> - add Aniyomi and Mihon repos (JSON and protobuf indexes) and list their extensions;
> - install, update, trust and uninstall extensions of both kinds (from their signed APKs only; only trusted ones get loaded);
> - browse and read manga sources, as in Suwayomi. Since Phase 5 Stage 1 a chapter's page images (upstream's page route) are fetched once per page even when asked for twice at once, retried once (an expired image URL is got anew first), answered with `SOURCE_FAILED` and the HTTP status when the source fails, and cached up to a cap (1 GB by default) with the least recently used pages going first. `prefetchChapterPages` warms the cache with a chapter's next pages in the background, and `readerSettings`, `setReaderSettings`, `pageCache` and `clearPageCache` set the cap, show what the cache holds and clear it (`<data dir>/reader-settings.json`);
> - browse anime sources: popular, latest, search with filters, details with episodes (or seasons), and the videos of an episode with their headers, subtitle and audio tracks. Source preferences can be changed, anime thumbnails are served through the backend, and anime can be added to and removed from the library (`updateAnime`, as upstream's `updateManga`);
> - see per extension which Android or extension APIs it needs and doesn't get (`compatIssues`);
> - play an episode's video in mpv (`playEpisode`, `stopPlayback`, `playbackSession`; Phase 4 Stage 1). The backend resolves the video right before the launch, and passes the source's headers and mpv options through a per-session config file and the subtitle and audio tracks over mpv's IPC pipe ([player/IPC.md](player/IPC.md));
> - follow playback (`playbackEvents`; Phase 4 Stage 2): the position, the watched mark, the end of the file with the next episode, and errors. Since Stage 4, RETRYING when mpv couldn't open the video and the backend fetches the videos again once, and ERROR with `retried`, `httpStatus`, `failedVideo` and `nextVideo` (the one to offer instead). The position and duration are saved into the episode (`lastSecondSeen`, `totalSeconds`, in milliseconds as the Aniyomi app stores them);
> - mark episodes watched, unwatched or bookmarked, or set their position (`updateEpisode`, `updateEpisodes`; Phase 4 Stage 3, in the shape of upstream's `updateChapter(s)`). Unwatched clears the position;
> - read and change the player settings (`playerSettings`, `setPlayerSettings`; Phase 4 Stage 5), kept in the data dir's `player-settings.json`, list the languages they can name (`playerLanguages`), and check an mpv (`mpvCheck`, `mpv --version`). `fetchEpisodeVideos` returns the hosters and videos sorted by the preferred server and quality after the source's own order, with `sourceIndex` and `matchesPreference`.
>
> **18 of the 20 most-downloaded yuzono anime extensions** work end to end against their real sites (install, browse, details and episodes, videos, a resolved video URL), measured by the compatibility harness ([docs/research/anime-compat.md](docs/research/anime-compat.md)). The two that don't:
> - AnimePahe: its site is behind a Cloudflare challenge, and a Cloudflare solver is deferred;
> - HentaiHaven: its video host was unreachable.
>
> Filters and preferences round-trip through the API: a filtered search works on all 18 with filters, and a preference change on 19 of 20. **9 of the 10 most-downloaded Keiyoushi manga extensions** read down to a page image ([docs/research/manga-compat.md](docs/research/manga-compat.md)); comix needs a real WebView, which is deferred with the Cloudflare solver. Uninstalling an extension frees its code: of the 20 anime extensions, 1 is still kept after uninstall, by a video connection of its own.
>
> See the [Roadmap](#roadmap).

---

## Features (planned)

- Runs Aniyomi (anime) and Mihon (manga) extension APKs directly, with no rebuilds or ports.
- Repos are **added by the user**. The app ships with no repos and no extensions.
- Anime playback in **mpv**: MKV/HEVC, ASS subtitles, HLS streams, and per-source HTTP headers.
- Manga reader with paged (LTR/RTL), vertical webtoon and double-page modes.
- Library, categories, update checker, history, and a download queue.
- Backup and restore.
- Later: trackers (AniList, MyAnimeList, Kitsu), Discord Rich Presence, torrent sources.

## How it works

Extensions are Android/Kotlin bytecode, so they need a JVM to run. Madoyomi has three parts:

1. A **Tauri 2 + Vue 3** desktop app (the UI).
2. A local **Kotlin/JVM backend** (based on Suwayomi-Server). It converts extension APKs with dex2jar, loads them with Android API stubs, and serves a local API.
3. **mpv** for video playback. It is launched with the stream's headers, subtitles and audio tracks.

```
┌──────────── Madoyomi (Tauri 2) ────────────┐
│  Vue 3 UI                                  │
│     │  HTTP + WebSocket (127.0.0.1)        │
│     ▼                                      │
│  Kotlin/JVM backend (bundled JRE)          │
│     ├─ extension host (APK → JVM)          │
│     ├─ repos, library, downloads, DB       │
│     └─ image proxy, Cloudflare shim        │
│                                            │
│  mpv  ← JSON IPC (named pipe)              │
└────────────────────────────────────────────┘
```

The full architecture and tech stack are in [docs/PLAN.md](docs/PLAN.md).

## Repository layout

| Path | What | Status |
|---|---|---|
| [`app/`](app/) | Tauri 2 shell (Rust, `src-tauri/`: backend sidecar lifecycle) + Vue 3 UI (`src/`: the app shell and routes; the Repos, Extensions, Browse, source, Details, Library and Settings screens; the filter and preference renderers; the typed API client and WebSocket in `api/`; vue-query hooks in `composables/`; the startup splash and error screens; design tokens; the video picker, live progress and bulk marks on episodes, the player settings; the manga reader. See [app/README.md](app/README.md)) | Phases 3, 4 and 5 done |
| [`server/`](server/) | Kotlin/JVM backend (Madoyomi-Server): extension host, API, DB. A fork of Suwayomi-Server at `7b061be`, rebranded and running in sidecar mode. Every change to upstream is listed in [server/UPSTREAM.md](server/UPSTREAM.md). Modules are listed below. | Phases 1, 2 and 4 done |
| [`player/`](player/) | mpv's config dir for Madoyomi (`mpv.conf`, `input.conf`) and the contract between the backend and mpv ([IPC.md](player/IPC.md)) | Phase 4 done |
| [`spikes/`](spikes/) | Phase 0 throwaway code: `index.pb` parser, Suwayomi GraphQL smoke test, anime extension harness, mpv playback check, Tauri sidecar | Done (Phase 0) |
| [`tools/`](tools/) | Dev scripts (tracked): `fetch-upstream-assets.ps1`, `fetch-test-apks.ps1`, `run-server.ps1`, `smoke-api.ps1` (the end-to-end API test against real repos) and its helpers in `lib/MadoyomiServer.ps1`, and `compat-test/` (the top-20 anime and manga-sample lists, the static compatibility scan, the compatibility harness `run-compat.ps1` for anime and manga with its triage file, and `lib/HprofLoaderRoots.java`, a heap-dump reader that shows what keeps an uninstalled extension in memory). Local binaries (`tools/bin/`), test APKs (`tools/apks/`, `tools/compat-test/apks/`) and upstream checkouts (`tools/upstream/`) are gitignored. | Active |
| [`docs/`](docs/) | Project plan and per-phase checklists | Active |

The backend in `server/` is a Gradle multi-module project:

| Module | What |
|---|---|
| `api` | Entry point (`madoyomi.api.MainKt`), `/health`, the API-token filter, Madoyomi's GraphQL additions (trust, extension events, the anime API: sources, browse, details and episodes, videos, filters and preferences, compat issues; and playback), the anime thumbnail route, and the integration tests of the whole API. Builds the server jar. |
| `core` | DB and migrations (manga and anime tables), the repo manager (JSON and protobuf indexes, repo trust), extension install/update/uninstall with the signing-certificate trust gate, the anime extension loader and anime services, the mpv player (`madoyomi.core.player`: finding mpv, its command line, the playback session, the JSON IPC client, progress and the watched mark), the extension compat log, manga domain, settings, the GraphQL API (type-aware for anime and manga) and the error model, the Javalin web server, startup |
| `ext-manga` | Mihon manga source API (`eu.kanade.tachiyomi.source`) |
| `ext-anime` | Aniyomi anime source API (`eu.kanade.tachiyomi.animesource`), ported from Aniyomi (lib 17, which keeps every lib-14/16 member). Trusted anime extensions run on it since Phase 2. |
| `ext-host` | What extensions run on: OkHttp network stack, helpers, the dex2jar bytecode fixers (including a repair for R8-optimised APKs), the extension classloader, APK inspection and signature verification (apksig) |
| `AndroidCompat` | Android API stubs that extensions call |
| `server-config`, `server-config-generate`, `i18n` | Settings, generated settings code, translations |

Dependencies run one way: `api → core → {ext-manga, ext-anime} → ext-host → {AndroidCompat, server-config}`. Package names stay as upstream's (`suwayomi.tachidesk.*`, `eu.kanade.tachiyomi.*`). New code goes under `madoyomi.*`.

## Prerequisites

Development targets **Windows 11**.

### Required now (app shell + UI)

| Tool | Version | Notes |
|---|---|---|
| [Node.js](https://nodejs.org/) | 22 LTS or newer | Needed by Vite 8 |
| [pnpm](https://pnpm.io/installation) | latest | `corepack enable` or `npm install -g pnpm` |
| [Rust](https://rustup.rs/) (via rustup) | stable, 1.85 or newer | The crate uses Rust edition 2024 |
| [Microsoft C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) | latest | Select the **Desktop development with C++** workload |
| [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) | latest | Already installed on Windows 11 |

The full list is in Tauri's guide: [Tauri prerequisites for Windows](https://v2.tauri.app/start/prerequisites/).

### Needed for the backend and Phase 0 spikes

| Tool | Notes |
|---|---|
| JDK 21 ([Temurin](https://adoptium.net/)) | Builds and runs the Kotlin backend and the spikes. The scripts expect it in `tools/bin/jdk-21` |
| [IntelliJ IDEA](https://www.jetbrains.com/idea/) | Recommended IDE for `server/` (Community edition is enough) |
| [dex2jar](https://github.com/ThexXTURBOXx/dex2jar), [jadx](https://github.com/skylot/jadx) | Convert and inspect extension APKs |
| [mpv](https://mpv.io/installation/) | Video playback. The app finds `mpv.exe` on PATH, or `tools/bin/mpv/mpv.exe`, or wherever `MADOYOMI_MPV` points (Phase 7 bundles it) |
| [protoc](https://github.com/protocolbuffers/protobuf/releases) | Decodes the manga repo's `index.pb` |
| ffmpeg, yt-dlp | Anime downloads (Phase 6) |

Put these tools in `tools/bin/`. That folder is gitignored, so binaries are never committed. APKs go in `tools/apks/` and are also never committed.

### Editor

[VS Code](https://code.visualstudio.com/) is set up for the repo. When you open the folder it offers to install the recommended extensions from [`.vscode/extensions.json`](.vscode/extensions.json) (Vue, ESLint, Prettier, Vitest, Tauri, rust-analyzer, Kotlin, Gradle, and others).

### Check your setup

```powershell
node -v
pnpm -v
rustc -V
cargo -V
java -version   # only needed for backend/spike work
```

## Getting started

```powershell
git clone https://github.com/james-tanuwidjaja/madoyomi.git
cd madoyomi/app
pnpm install
pnpm tauri dev
```

`pnpm tauri dev` starts the Vite dev server and opens the desktop window. The first run compiles the Rust shell and takes a few minutes. Later runs are much faster.

To work on the UI only, run `pnpm dev` and open <http://localhost:1420> in a browser. Tauri APIs are not available in this mode, so there is no backend, and a banner says so. To use a backend anyway, start one with `.\tools\run-server.ps1` and put the base URL and token it prints into `app/.env.local` (gitignored) as `VITE_MADOYOMI_URL=http://127.0.0.1:<port>` and `VITE_MADOYOMI_TOKEN=<token>`, then restart `pnpm dev`.

### Backend (`server/`)

`server/` is a Suwayomi-Server fork. Upstream's binary assets (Noto fonts for the Android `Typeface` stub, and test images) are not committed. Restore them once after cloning, before the first Gradle build:

```powershell
.\tools\fetch-upstream-assets.ps1
```

The script copies each file from `tools/upstream/Suwayomi-Server` when that checkout is at the pinned commit. Otherwise it downloads the file from GitHub. Either way it checks the file's SHA-256.

Build and test the server (the first build takes about 4 minutes):

```powershell
cd server
$env:JAVA_HOME = (Resolve-Path ..\tools\bin\jdk-21).Path
.\gradlew.bat build
```

`build` runs the ktlint check and all tests. If ktlint reports style violations, `.\gradlew.bat ktlintFormat` fixes most of them; check `git diff` afterwards. The server jar is `server/api/build/Madoyomi-Server-v0.1.0.jar` (built by `.\gradlew.bat :api:shadowJar`).

Run it locally the way the app will start it:

```powershell
.\tools\run-server.ps1            # builds the jar, then runs it
.\tools\run-server.ps1 -NoBuild   # runs the last built jar
```

The script generates an API token, starts the server with a dev data dir in `tools/dev-data/` (gitignored), and prints the token. Once the server prints `MADOYOMI_PORT=<port>`, the script prints the base URL and example requests. Every request except `GET /health` needs `Authorization: Bearer <token>`. Stop it with Ctrl+C. The full startup contract (arguments, environment, endpoints) is in [server/UPSTREAM.md](server/UPSTREAM.md#startup-contract-what-the-tauri-shell-relies-on).

End-to-end smoke test against the real repos (needs internet access):

```powershell
.\tools\smoke-api.ps1            # builds the jar, then runs the test
.\tools\smoke-api.ps1 -NoBuild   # tests the last built jar
```

The script starts the jar the way the app does, on a throwaway data dir under `%TEMP%`, and drives it over GraphQL only. It adds yuzono (anime) and Keiyoushi (manga), lists both kinds' extensions, and installs and checks AnimeGG and MangaPill while a WebSocket subscription follows the installs. It then reads a MangaPill chapter down to a page image, and runs AnimeGG from popular through details and episodes to the videos of the newest episode, a resolved video and a thumbnail. It prints PASS/FAIL per step and exits non-zero if any step fails. `Get-Help .\tools\smoke-api.ps1 -Detailed` lists the options (another jar, data dir or repo URLs).

> [!NOTE]
> `pnpm tauri dev` also starts the real backend, the newest `server\api\build\Madoyomi-Server-*.jar`. Build it once first: `.\gradlew.bat :api:shadowJar` in `server/` (see [Backend](#backend-server)). The shell runs it with `tools/bin/jdk-21`, a fresh API token per app session and `--data-dir %APPDATA%\Madoyomi`, and keeps its stdin open; the backend exits when the app does.
> - **Startup.** The window shows "Starting the backend…" until `/health` answers, about 10–12 s (longer on a busy machine; after 30 s it says so). The shell keeps waiting for up to 5 minutes while the JVM is alive.
> - **Crashes.** If the backend exits or stops answering, the app shows an overlay and restarts it after 1, 2, 4 and 8 s. After 5 failed starts in a row it stops and offers Retry and Open logs. A missing jar shows that error at once, and Retry looks for it again, so building the jar and pressing Retry is enough. Once the backend is back (on a new port), the UI's requests and its WebSocket move there by themselves, and the sidebar's footer shows "Live updates" again.
> - **Logs.** `sidecar.log` (the backend's output plus the shell's start, port, health and exit lines) and the server's `madoyomi.log` are in `<data dir>\logs`.
> - **mpv.** Play needs mpv. A path set in Settings → Player comes first. Otherwise the shell passes the backend `tools/bin/mpv/mpv.exe` if it's there, and without it the backend uses `mpv.exe` from PATH. It runs mpv with the repo's `player/` as its config dir. mpv's output is in `<data dir>\logs\mpv.log`, and `sidecar.log` says which mpv and config dir the backend was given. The backend drives mpv over `\\.\pipe\madoyomi-mpv-<session>`; mpv accepts several clients, so a script can read its properties there too.
> - **Environment overrides,** set before `pnpm tauri dev`: `MADOYOMI_SIDECAR_JAR` (another jar), `MADOYOMI_JAVA` (another `java.exe`), `MADOYOMI_DATA_DIR` (another data dir, e.g. a fresh one for testing), `MADOYOMI_MPV` (another `mpv.exe`), `MADOYOMI_PLAYER_DIR` (another mpv config dir).
> - **One server per data dir.** Don't run `pnpm tauri dev` and a server of your own on the same data dir at once (the database is locked). `tools/run-server.ps1` uses `tools/dev-data/`, so the two don't clash.
>
> The Phase 0 dummy sidecar in `spikes/sidecar` is no longer used by the app.

## Common commands

Run these from `app/`:

| Command | What it does |
|---|---|
| `pnpm tauri dev` | Run the desktop app (Vite + Rust shell, hot reload). It starts the backend jar from `server/api/build` (see the note above) |
| `pnpm dev` | UI only, in a browser at http://localhost:1420 (no backend, unless `VITE_MADOYOMI_URL` and `VITE_MADOYOMI_TOKEN` are set in `.env.local`) |
| `pnpm test` / `pnpm test:watch` | Run Vitest once / in watch mode |
| `pnpm vitest run src/api/__tests__/operations.live.spec.ts` | Check the UI's GraphQL operations against a running server: validates every document against its schema, checks the token, receives a WebSocket event, runs a filtered search on an installed source of each kind (it needs the source's site), refreshes an entry and puts it in the library and out, changes a preference of a configurable source of each kind and restores it, reads a manga chapter's pages through the backend with a prefetch, then saves and restores its progress, and sets, reads and removes a manga's reader settings in its meta (Phase 5). Set `MADOYOMI_LIVE_URL` and `MADOYOMI_LIVE_TOKEN` (e.g. from `tools/run-server.ps1`) first; otherwise these tests are skipped, as in `pnpm test` |
| `pnpm lint` / `pnpm lint:fix` | ESLint check / auto-fix |
| `pnpm format` / `pnpm format:check` | Prettier write / check |
| `pnpm typecheck` | Type-check with `vue-tsc` |
| `pnpm build` | Type-check and build the UI into `dist/` |
| `pnpm tauri build` | Build NSIS + MSI installers into `src-tauri/target/release/bundle/` |
| `cargo test` / `cargo clippy --all-targets` (in `app/src-tauri/`) | Rust shell unit tests (port line, backoff, `/health`, log timestamps, where the jar and mpv are found) / Rust lints |

More detail is in [app/README.md](app/README.md).

Backend commands. Run the Gradle ones from `server/`, with `JAVA_HOME` set to `tools/bin/jdk-21`:

| Command | What it does |
|---|---|
| `.\gradlew.bat build` | Compile all modules, run ktlint and all tests |
| `.\gradlew.bat :core:test` | Run the server tests only |
| `.\gradlew.bat :api:shadowJar` | Build `server/api/build/Madoyomi-Server-v0.1.0.jar` |
| `.\gradlew.bat ktlintFormat` | Reformat Kotlin sources |
| `.\tools\run-server.ps1` (from the repo root) | Build and run the server with a dev data dir and a fresh token |
| `.\tools\smoke-api.ps1` (from the repo root) | End-to-end API test against the real yuzono and Keiyoushi repos |
| `.\tools\fetch-upstream-assets.ps1` (from the repo root) | Restore the upstream fonts and test images |
| `.\tools\fetch-test-apks.ps1` (from the repo root) | Download the extension APKs the tests use into `tools/apks/` |
| `.\tools\compat-test\scan.ps1` (from the repo root) | Download the top-20 anime and manga-sample APKs and run the static compatibility scan into `docs/research/compat-scan.md` |
| `.\tools\compat-test\run-compat.ps1 -NoBuild` (from the repo root) | Compatibility harness: run the top 20 anime extensions on the real server jar (about 8 min, needs internet access), with process health, into `docs/research/anime-compat.md`. Per extension it also round-trips a preference and runs a filtered search, and after uninstalling all of them it reports what still keeps their code in memory (a heap dump, read by `lib/HprofLoaderRoots.java`). `-Only <names>` reruns a few into `tools/compat-test/out/`; `-ReportOnly` redraws the report after a `triage.txt` edit |
| `.\tools\compat-test\run-compat.ps1 -NoBuild -Kind Manga` (from the repo root) | The same harness over the 10-extension Keiyoushi manga sample (popular, details and chapters, pages, a page image) into `docs/research/manga-compat.md`: the "no regression in manga" check (about 2 min) |

## Roadmap

Status: ✅ Done · 🟡 In progress · ⚪ Not started

| Phase | Focus | Status | Details |
|---|---|---|---|
| 0 | **Spikes:** prove manga extensions, the `index.pb` index, anime extensions, mpv playback and the Tauri sidecar each work on their own | ✅ Done | [phase-0-spikes.md](docs/phases/phase-0-spikes.md) |
| 1 | **Backend foundation:** fork and rebrand Suwayomi-Server, add an anime domain, repo manager for both index formats, extension install/trust | ✅ Done | [phase-1-backend-foundation.md](docs/phases/phase-1-backend-foundation.md) |
| 2 | **Anime sources:** Aniyomi `animesource` API, Android stubs, filters and preferences as JSON | ✅ Done (18/20 top anime extensions end to end, filters and preferences through the API, 9/10 of the manga sample; Cloudflare/WebView shim deferred) | [phase-2-anime-sources.md](docs/phases/phase-2-anime-sources.md) |
| 3 | **Frontend MVP:** Browse, Details, Library, Extensions, Settings screens; generic filter and preference renderers | ✅ Done (every screen for anime and manga, the generic filter and preference renderers, the backend run and restarted by the app; every exit criterion checked from a fresh data dir in the UI) | [phase-3-frontend-mvp.md](docs/phases/phase-3-frontend-mvp.md) |
| 4 | **Playback:** mpv launcher, IPC, progress sync, video picker, next episode | ✅ Done (mpv in its own window, owned by the backend: the launcher, the IPC pipe, progress and the watched mark, `playbackEvents`; the video picker with the last choice per source and the resume prompt, live progress and the Watched badge, bulk marks; the next-episode toast with auto-play; one refetch on expired URLs, then the next video; Settings → Player; the format matrix (MP4, HLS, MKV with ASS, external subtitles and audio, expiring URLs) and every exit criterion checked from a fresh data dir; `--wid` embedding is a stretch goal, the header proxy deferred) | [phase-4-playback.md](docs/phases/phase-4-playback.md) |
| 5 | **Manga reader:** image proxy and cache, paged, webtoon and double-page modes | ✅ Done (the image pipeline around upstream's page route: one fetch per page, a retry that gets expired image URLs anew, `SOURCE_FAILED` errors, the 1 GB cache with LRU eviction, `prefetchChapterPages`; the reader in Single (both directions), Double (spread detection, "Shift") and a virtualized Webtoon strip, with zoom and pan, the seamless next chapter, at most 4 page requests and the backend prefetching ahead; progress saved and resumed; the mode and direction per manga, webtoon by genre or site, the page fit, strip width, background and preloading as settings; every exit criterion checked from a fresh data dir, the 103-page scroll measured on the dev desktop) | [phase-5-manga-reader.md](docs/phases/phase-5-manga-reader.md) |
| 6 | **Library features:** categories, updates, history, downloads, backup/restore | ⚪ Not started | [phase-6-library-features.md](docs/phases/phase-6-library-features.md) |
| 7 | **Packaging & release:** jlink JRE, bundled mpv, installers, auto-update, CI | ⚪ Not started | [phase-7-packaging-release.md](docs/phases/phase-7-packaging-release.md) |
| 8 | **Later:** trackers, Discord Rich Presence, torrent sources | ⚪ Not started | [phase-8-later.md](docs/phases/phase-8-later.md) |

The checklists in each phase document are the source of truth. This table is a summary and should be updated when a phase changes status.

### Open decisions

These are tracked in [docs/PLAN.md §7](docs/PLAN.md#7-open-decisions).

Decided in Phase 0 (see [phase-0-spikes.md §9](docs/phases/phase-0-spikes.md)):

- **Fork Suwayomi-Server** rather than writing a fresh backend.
- **Extend Suwayomi's GraphQL API**, keeping REST only for binary endpoints (images, pages).

Decided in Phase 1 (see [phase-1-backend-foundation.md §8 and §9](docs/phases/phase-1-backend-foundation.md#9-decision-record)):

- **One JVM process** for the backend and both extension types (final). Crash isolation is deferred, and the trust gate keeps untrusted code from loading at all. **Confirmed by Phase 2:** two harness runs of the top 20 in one JVM had no crash, heap and threads stayed bounded, and uninstalled extensions are freed (1 of 20 is kept by a connection of its own).

- **Plain copy of upstream** into `server/`, first committed unmodified as a baseline, with the upstream commit recorded in [server/UPSTREAM.md](server/UPSTREAM.md).
- **Keep upstream package names** (`suwayomi.tachidesk.*`). New code goes under `madoyomi.*`, which keeps upstream merges manageable.
- **Keep H2** as the database.
- **Mihon trust model:** adding a repo trusts its signing key. Any other certificate needs explicit trust, and untrusted extensions are never loaded.

Decided in Phase 2 (see [phase-2-anime-sources.md §9](docs/phases/phase-2-anime-sources.md#9-decision-record)):

- **One anime API surface, the newest** (Aniyomi's lib 17, which keeps every lib-14/16 member), checked per extension by a static link check instead of running lib versions side by side.
- **The "top 20" is ranked by jsDelivr downloads** of yuzono's APKs, NSFW included, torrent extensions excluded.
- **Cloudflare: measure first.** A Cloudflare solver is only added if blocked sites push the compatibility result below 18 of 20. Measured in Stage 5: only AnimePahe of the top 20 is blocked, and 18 of 20 pass without a solver, so the solver is **deferred**.
- **Extension code runs on large-stack threads** (128 MB). Java's regex engine recurses where Android's doesn't, and one top-20 extension (AniZone) overflowed a normal thread stack on a long page.
- **Filters and preferences use upstream's GraphQL types**, so one renderer serves anime and manga.
- **Uninstall frees the extension's code.**
  - Threads the extension left running are stopped: its own thread pools are shut down, others are interrupted, and a local server blocked on its socket has that socket closed. Nothing is forced, and a thread that keeps running is logged.
  - Shared objects no longer hold extension objects: each extension gets its own JSON instance, and preference listeners are held weakly, as on Android.
- **No regression in manga** is checked with the harness's manga mode over the 10 most-downloaded Keiyoushi extensions. A source that needs a real WebView (comix) counts as deferred, not as a regression.

Decided in Phase 3 (see [phase-3-frontend-mvp.md §9](docs/phases/phase-3-frontend-mvp.md#9-decision-record)):

- **UI components: headless primitives with our own styles.** [Reka UI](https://reka-ui.com/) provides accessible dialogs, selects, tabs, checkboxes and switches. They are styled only through Madoyomi's design tokens, and long lists are virtualized with TanStack Virtual.
- **Dark and light themes from the start.** The app follows the Windows setting, and Settings offers System, Dark or Light. The choice is kept and applied before the window first paints.
- **A Windows 11 / Fluent-like look:** Segoe UI Variable, neutral layered surfaces, small radii, subtle borders, one accent colour, compact density.
- **The app runs the backend itself.** It gets a new API token every session, which is never logged. Data lives in `%APPDATA%\Madoyomi`, the same place a server started by hand uses. The shell waits patiently while the JVM starts. After a crash it restarts the backend with a backoff (1, 2, 4, 8 s), and after 5 failed starts in a row it stops and offers Retry. Logs are in `%APPDATA%\Madoyomi\logs`.
- **Hand-written API types, checked against the real server** instead of generated, so there's no codegen step. A live test validates every GraphQL operation the UI sends against the server's schema.
- **Our own small WebSocket client** for live events, because the common library insists on a subprotocol the server doesn't negotiate. The app follows the backend's events for its whole lifetime and refreshes the affected lists. After a reconnect it fetches them again, since the server doesn't replay missed events.
- **Requests retry only while the backend is down or restarting.** An error the backend reports (for example, a source's site failing) is shown, not retried.
- **The native Windows title bar stays** (Snap Layouts, the usual caption buttons). The sidebar's anime/manga switch is remembered between sessions.
- **A repo's type is worked out by the app.** It tries anime or manga, guessing from the URL. If the backend says the repo's extensions are of the other kind, it tries that kind instead. You can also pick the type yourself. The backend didn't need to change for this.
- **Refreshing works per kind:** all anime repos, all manga repos, or everything, because the backend refreshes a kind at a time. Removing a repo asks first and says which installed extensions will stop loading.
- **The trust prompt** shows the extension, its repo and its signing-certificate hash (with Copy), and warns that a trusted extension runs with your rights. It also opens by itself after you install an extension whose certificate isn't trusted, as in Mihon.
- **18+ extensions are hidden from the Available list** until you turn on "Show 18+". The app remembers the setting. Installed ones always show.
- **Long lists stay fast:** the extension list only draws the rows on screen (Keiyoushi lists 1469 extensions).
- **Pinned sources are kept on this device** (like Mihon's pinned sources), per kind. The backend has per-source settings only for manga, so storing pins there would have needed a backend change for anime.
- **Filters apply to a search**, as on the backend and in Mihon: Apply switches to Search, with or without a query (many sources use their filters only without one). Only the filters you changed from the source's defaults are sent, and the backend applies them to a fresh filter list, so the empty list is the source's defaults. Reset puts the defaults back.
- **Each source remembers its tab, search and filters while the app runs**, not across restarts. Stored filters are dropped when an extension update changes the source's filter list.
- **The filter controls** are Reka's Select (for selects and sort fields, with an Ascending/Descending button), checkboxes, a three-state checkbox (include ✓, exclude ✕, ignore) and collapsible groups.
- **A source the backend can't use isn't opened.** An untrusted or unloaded one points to Extensions (its Installed tab, searched for that extension), where the trust prompt or the error is. The built-in Local source is never shown as untrusted.
- **Result grids stay fast too:** only the rows on screen are drawn, the column count follows the window width, and the next page loads when you near the end. Covers come from the backend's thumbnail routes, which apply the source's headers, so no separate image proxy was needed.
- **Details refresh by themselves only once.** An entry that was never fetched is refreshed from its source the first time you open it in a session; after that only the Refresh button asks the site again. A failure keeps the stored data on screen with a readable error. A source that's missing, untrusted or not loaded isn't asked: the page says why and points to Extensions.
- **Manga refresh in two steps** (details, then chapters), so the source's own error code reaches the app. The backend's one-call version reports a site failure without its code.
- **The episode and chapter list's sort is remembered per kind** (default: the source's order, newest first, as in Mihon). Its filters (bookmarked, scanlator, and watched/read, which only Phase 6 records) apply to that entry for the session.
- **The library flag is the backend's** (a small backend addition, `updateAnime`, as upstream's `updateManga`). Adding or removing an entry also marks its cover on the source pages you already loaded, without browsing the source again.
- **The Library sorts by date added (newest first) or title**, per kind, remembered between sessions.
- **Source settings save as you change them**, and the change shows at once. Changes go to the backend one at a time, in order. If a save fails, the old value comes back with the error; if the extension refuses a value, the page shows the one it kept. Lists, switches and checkboxes apply at once; a text or a multi-select opens a dialog with Save, as on Android.
- **A changed setting takes effect on the next page:** the app drops the source's loaded pages, so it browses again with the new settings, and it reads the source's address again after that (a mirror setting changes it).
- **Manga settings are read before each change.** The backend's manga side (Suwayomi's) needs a read first, and has none right after a restart. It also can't refuse a value the way anime extensions can: it saves first, as upstream does. No backend change was needed.

- **Covers and chapter pages are cached in the data dir** (`<data dir>\cache`), next to the database whose ids name the files. Suwayomi keeps these caches in the temp folder, shared by every data dir, and a fresh data dir showed an older one's covers. A small backend change, found in the exit run.
- **UI preferences belong to this device, not to the data dir:** pins, the theme, the anime/manga switch, the sorts and "Show 18+" stay when the data dir changes.

Decided in Phase 4 (see [phase-4-playback.md §9](docs/phases/phase-4-playback.md#9-decision-record)):

- **mpv plays in its own window** for the MVP: its own fullscreen, controls and keys. Embedding it in the app window is a stretch goal after the phase's exit run.
- **The backend owns mpv.** It resolves the video right before the launch, keeps the source's video URLs and headers to itself (the UI never sees them), and runs one mpv at a time. A new Play replaces the episode that was playing.
- **Where mpv comes from:** the path in Settings → Player (Stage 5; a wrong one is `PLAYER_NOT_FOUND` naming the setting), else `MADOYOMI_MPV`, else `mpv.exe` on PATH. The app sets `MADOYOMI_MPV` itself to `tools/bin/mpv/mpv.exe` in dev, or to the bundled mpv in a release build (Phase 7), when that file exists. mpv's config is Madoyomi's own (`player/`), not your `%APPDATA%\mpv`.
- **The source's headers, subtitles, audio tracks and mpv options go to mpv in a per-session config file,** not on its command line. Values with quotes or commas arrive exactly, and nothing private shows in the process list. Each header is sent on its own (Phase 0's finding), and options that would take control of mpv away from Madoyomi (scripts, config, the IPC pipe, written files) are left out.
- **mpv never outlives the app.** It closes when the app closes or is killed. If the backend crashes, the restarted backend closes the mpv it left. mpv doesn't get the API token.
- **Progress goes into the episode's existing watched and position fields** (Aniyomi's model, no new table), **in milliseconds** as the Aniyomi app stores them, despite the field names, so backups agree. It is saved every 5 s while playing, and at once on pause, seek and quit.
- **An episode is marked watched once per session, at 85 %** (Settings → Player since Stage 5, 50–95 %). If you unmark it while it plays, that session doesn't mark it again.
- **The backend talks to mpv over its named pipe** and stops it with `quit` (mpv exits normally) before it would kill it. Subtitle and audio tracks are added over the pipe, so mpv's menus show their language rather than a file name. When a stream has no audio of its own, the first audio track is selected.
- **Playback events** (`playbackEvents`): STARTED, PROGRESS, WATCHED, ENDED with the next episode, ERROR, CLOSED. Quitting past 85 % counts as the end, so the next episode can be offered.
- **Play always opens the video picker,** with your remembered choice (else the source's pick) preselected and focused, so Enter plays it. The source is asked for the videos each time anyway, since their URLs expire.
- **Your last choice is remembered per source, on this device** (the server's name and the video's title or resolution, since indexes change between episodes), and saved when mpv starts.
- **Resume between 1 % and 85 %:** the picker offers "Resume from mm:ss" and "Start over". A watched episode, or one below 1 %, starts from the beginning without asking.
- **Unmarking an episode clears its position,** as the Aniyomi app does, so it starts over. Marking it watched keeps the position.
- **Live progress without refetching:** the playing row follows the playback events (time left, Playing/Paused, a bar), and the Watched badge appears the moment the episode passes 85 %. Only the watched mark and the end of a session refetch the list.
- **"Mark previous as watched"** marks every earlier unwatched episode in the source's order, whatever the list's sort.
- **The next episode:** when an episode ends (or you quit past 85 %), a toast offers the next one in the source's order and plays it after **10 s**, with your remembered choice and without the picker, from where you stopped in it (between 1 % and 85 %), else from the start. **Auto-play is on by default** (Settings → Player, on this device). Cancel, another Play or leaving the page stops it; if it can't play, the picker opens.
- **Expired videos are fetched again once:** when mpv never got the video playing, or its log shows an HTTP 401/403/404/410 (a URL that expired while playing ends the file early with a 403), the backend fetches the episode's videos again, finds the same video and plays it from where it failed. If that fails too, the picker reopens with the error and the next video in the source's order selected.
- **No header proxy for now:** every header the measured sources send (AnimeGG 2, KickAssAnime 7) reaches mpv as it is, so the proxy fallback waits until a source needs it.
- **The player settings are the backend's** (Stage 5), in the data dir's `player-settings.json`, since the backend applies them: the watched mark, the video order, the tracks it selects and which mpv it starts. A fresh data dir starts from the defaults. Auto-play stays on this device.
- **The watched threshold is 50–95 %, in steps of 5.** 100 % isn't offered: mpv's last position stops just short of the duration, so it would never mark. A change also applies to the episode playing.
- **Preferred quality and server sort the videos after the source's own order:** servers whose name contains the preferred one first, then in each server the preferred quality, else the closest below it, else the closest above. The picker marks the result **Preferred**; "Source's pick" still means the source's own.
- **Your last choice for a source comes first** (your decision): the picker preselects it, and auto-play plays it; the preference decides for a source you haven't chosen on yet, else the source's pick.
- **Subtitle and audio language:** once the tracks load, the backend selects the first track in that language, the video's own before the source's, matched by name or code ("English (eng)", "eng", "en-US"); subtitles can be off. With no match, the source's first subtitle track, and the video's own audio (else the source's first).
- **A video that never plays a frame is fetched again once too** (the exit run): mpv reports where it was to start as soon as the stream opens, so an HLS stream whose pieces all fail now counts as never played, like a refused link.
- **The exit run's MKV was a generated file:** none of the 20 most-used anime extensions serves MKV, so a test MKV (ASS subtitles, two audio tracks) stood in for one video's link; everything else on the way was the real thing.
- **Embedding mpv in the app window stays a stretch goal** after the phase, and the header proxy stays deferred: no source needed a header mpv can't send.

Decided so far in Phase 5 (see [phase-5-manga-reader.md §9](docs/phases/phase-5-manga-reader.md#9-decision-record)):

- **Six stages, all done:** the image pipeline, the reader with the paged modes and progress, webtoon, double page with zoom and the next chapter, the reader settings, and the exit run.
- **The default reader mode** (your decision): a manga's own mode first; else webtoon when its genres say Long Strip, Webtoon, Manhwa or Manhua (Mihon's sources give no hint of their own); else (your decision in Stage 5) webtoon when its source's name says Webtoon, such as Webtoons.com, whose titles carry only genres like Fantasy; else paged right-to-left, Mihon's default, which you can change.
- **The page cache** (your decision): 1 GB by default, 256 MB to 10 GB in Settings → Reader, in the data dir's `cache\manga` folder (where Suwayomi's page cache already is). Past the cap, the pages read longest ago go first; pages used in the last 5 minutes always stay. Downloads and covers don't count.
- **No separate image proxy:** Suwayomi's page route already fetches each page through its source, with the source's headers, cookies and rate limits, so Madoyomi adds what it lacked around that fetch instead.
- **A page that fails is tried once more.** If the site says the image is gone or forbidden (a link that expired: Suwayomi keeps a chapter's page list from when it was opened), its link is got again first. Then the reader gets a clear error with the site's HTTP status, for a "tap to retry".
- **The prefetch is the backend's:** the reader asks for the next pages and the next chapter's first ones, and the backend fetches them two at a time, so the pages on screen aren't held up.
- **Reading progress uses Suwayomi's own chapter fields** (last page read, read, when), so there is no new table. The cache's cap is a backend setting (it applies it); the reader's own preferences will live on this device, and per manga with the manga.
- **The reader has the whole window** (`/manga/:id/read/:chapterId?page=N`, without the sidebar), and the page is in its address, so it reopens on the same page.
- **Paged, right to left by default:** the left side of the page, ← and the wheel down go forward; the toolbar flips to left to right, for this manga (Stage 5). The middle of the page shows the toolbar.
- **A chapter opens where you stopped,** unless it is read: a read chapter starts at page 1, as in Mihon.
- **Your page is saved a second after you stop paging** (and when you leave), the chapter is marked read when its last page shows, and a save that fails is tried again until the backend answers.
- **Between chapters there is a panel** ("Finished … Next chapter: …", or "There's no next chapter."), so one extra click doesn't leave the chapter. Since Stage 4 it is the seamless transition: one more step goes on into that chapter at once, in place.
- **The next chapter is the next in the source's order;** another group's copy of the chapter you just read is skipped, and your group's copy of the next one is preferred. Chapter numbers alone aren't trusted: MangaPill's "Group 2 Chapter 385" is number 2.
- **Pages load 4 at a time around the one shown** (one behind, four ahead), the page shown first, and the backend fetches the next ten into its cache. The token goes in a request header, never in a page's address.
- **Marking a chapter unread sends it back to page 1,** as in Mihon. "Continue reading" opens the chapter you read last, or the one after it once it's finished.
- **The webtoon strip is laid out by computation, not measurement** (Stage 3): each page is as tall as its image's ratio makes it at the strip's width (at most 800 px, Webtoons' own width), and a page not loaded yet as tall as the others' median, so only the pages near what you see are in the window. When a page's real size arrives or the window changes, what you look at stays put.
- **Pages load by distance in the strip:** a screen behind and two ahead (at least two pages), still 4 at a time, each decoded before it shows; pages further away are dropped. Very tall images (a whole chapter in a few) show whole, with no tiling.
- **The page saved in the strip is the one at the top of the window,** so a chapter reopens with that page at the top. Reaching the end of the strip marks the chapter read, even if a fast scroll skipped the last page.
- **In the strip,** the wheel scrolls natively, ↓/↑ a little and Space/Page Down/Page Up a screen; past the end the next chapter opens, past the top the previous one. Switching modes keeps your page.
- **Double page** (Stage 4) is a mode of the paged reader, not a separate one: **page 1 alone** (your decision; "Shift" pairs from page 1, for the session), and a page wider than tall alone, with the pairs starting again after it. A page not loaded yet counts as portrait, so the pairs can change as sizes arrive, but the page you're on stays on screen. Right to left, the first page of a pair is on the right. The later page of a pair is the one saved, so the last pair marks the chapter read.
- **Zoom** (Stage 4), in the single and double modes: Ctrl+wheel, Ctrl+=/−/0, and a **double-click in the middle third only** (your decision: clicks on the sides still turn the page at once; the middle's single click, the toolbar, waits a quarter of a second). From fit to 4×, never past the page's edges; a drag, the wheel or the arrows move around; a page turn or Esc goes back to fit. There's no zoom in the webtoon strip (Stage 5's fit setting is the place for a wider strip).
- **The seamless next chapter** (Stage 4): the chapter's page list is fetched a few pages before the end (and the previous one's on its panel), so going on opens it at once. **The webtoon strip stays one strip per chapter** (your decision): a new wheel gesture at its very bottom opens the next chapter at its top, and a fling that only reaches the bottom doesn't.
- **The mode switch** is Single / Double / Webtoon. Toolbar buttons clicked with the mouse don't keep the keyboard focus, so Space turns the page afterwards; Tab still reaches them.
- **Where the reader settings live** (Stage 5): a manga's mode and direction in the manga's meta (Suwayomi's own, so they go with it into backups), keyed `madoyomi.reader.mode` and `madoyomi.reader.direction`; everything else on this device (`madoyomi.reader` in the webview's storage), as Mihon keeps only the mode and direction per manga. A value the app doesn't know (a hand edit, a later version's) counts as not set. **The toolbar's mode and direction save this manga's own**, as in Mihon; the page gap changes the global setting, and Shift stays for the session.
- **The page fit** (Stage 5, single and double): fit screen (the whole page, as before) by default, or fit width, fit height or original size. A page bigger than the window opens at its top (on the reading direction's side when it is wider) and scrolls with the wheel, ↓/↑, Space, Page Up/Down or a drag; at its edge the next step (a new wheel gesture) turns the page, which opens at its top. Zoom scales the fitted page.
- **The strip width** (Stage 5): 800 px by default (as before), or 600, 1000, 1200 px or the window's; what you look at stays put when it changes. **The background** is dark (#111, as before), black, gray or white; the reader's text turns dark on white.
- **Preloading** (Stage 5): 4 pages ahead by default (as before), 2 to 12. In the strip it is that count minus the two on screen, at least two. The backend's block of ten starts after them. The next chapter's first five pages and the 4 requests at a time don't change.
- **The reader waits for the manga's own settings** before it shows a page, so the mode doesn't jump; if they can't be read, it uses the defaults.
- **The smooth-scroll exit criterion was checked on the dev desktop** (your decision in Stage 6): it names a mid-range laptop, and none was at hand. The 103-page chapter scrolled with no frame over 50 ms and no blank frame there.
- **The next chapter's first pages are ready before you get there** (Stage 6): once its page list is fetched ahead, the reader also loads the page it opens on and the one after (the previous chapter's last two on its panel), so going on shows them at once, with no blank flash.
- **A double-page chapter keeps the page you left it on** (Stage 6): until you turn, reopening it saves the page it opened on, not the other page of the pair, so its place doesn't creep forward when the pairs fall differently (Shift is for the session).
- **Changing the page fit starts the page over** (Stage 6): it opens at its top, or whole, even if you had panned or zoomed.

Still open:

- Windows-only, or keep macOS/Linux possible?

## Contributing

The project is private and solo for now. The workflow:

1. Read [docs/PLAN.md](docs/PLAN.md) and the relevant phase document before starting work.
2. Tick checklist items in `docs/phases/` only when they are done **and verified**.
3. Before opening a PR, run `pnpm lint`, `pnpm typecheck` and `pnpm test` in `app/`. For backend changes, also run `.\gradlew.bat build` in `server/`.
4. Work on a branch and open a pull request into `main`.
5. Never commit APKs, `.dex` files, jars, tool binaries, or the upstream fonts and test images restored by `tools/fetch-upstream-assets.ps1`.
6. When you change an upstream file in `server/`, mark the edit with a `// Madoyomi:` comment and list it in [server/UPSTREAM.md](server/UPSTREAM.md).

## Acknowledgements

- [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) (MPL-2.0): the backend base and the manga extension host.
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [Mihon](https://github.com/mihonapp/mihon) (Apache-2.0): the extension APIs Madoyomi implements.
- [mpv](https://mpv.io/) for playback and [Tauri](https://tauri.app/) for the desktop shell.

## Disclaimer

Madoyomi does not host, ship or provide any content or extensions. Users add extension repositories themselves and are responsible for the sources they use. Madoyomi is not affiliated with Aniyomi, Mihon, Suwayomi, or any extension or content provider.

## License

[MPL-2.0](LICENSE). Files derived from upstream projects keep their original license headers. `server/` is a fork of Suwayomi-Server (MPL-2.0); see [server/UPSTREAM.md](server/UPSTREAM.md). The extension APIs from Mihon and Aniyomi are Apache-2.0. Attribution is in [NOTICE](NOTICE), and all third-party code and notable libraries are listed in [THIRD_PARTY.md](THIRD_PARTY.md).
