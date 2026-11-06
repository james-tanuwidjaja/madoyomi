# Madoyomi

A Windows 11 desktop app for anime and manga that runs existing **Aniyomi** and **Mihon** extensions unchanged.

The name comes from 窓 *mado* ("window") + 読み *yomi* ("reading").

> [!WARNING]
> **Early development. Not usable yet.** Phase 0 (spikes), **Phase 1 (backend foundation)** and **Phase 2 (anime sources)** are done. **Phase 3 (frontend MVP)** is in progress: the Vue UI on the real backend. Stage 1 of 7 is done. The desktop app now starts the real backend itself, with a fresh API token and its data dir (`%APPDATA%\Madoyomi`). It shows a "starting" splash until the backend is healthy, and restarts the backend on its own if it crashes (with an overlay that says so). The screens come next.
>
> The backend in `server/` is a Suwayomi-Server fork, rebranded as Madoyomi-Server. It runs as a local sidecar (loopback only, random port, API token). From its GraphQL API you can:
> - add Aniyomi and Mihon repos (JSON and protobuf indexes) and list their extensions;
> - install, update, trust and uninstall extensions of both kinds (from their signed APKs only; only trusted ones get loaded);
> - browse and read manga sources, as in Suwayomi;
> - browse anime sources: popular, latest, search with filters, details with episodes (or seasons), and the videos of an episode with their headers, subtitle and audio tracks. Source preferences can be changed, and anime thumbnails are served through the backend;
> - see per extension which Android or extension APIs it needs and doesn't get (`compatIssues`).
>
> **18 of the 20 most-downloaded yuzono anime extensions** work end to end against their real sites (install, browse, details and episodes, videos, a resolved video URL), measured by the compatibility harness ([docs/research/anime-compat.md](docs/research/anime-compat.md)). The two that don't:
> - AnimePahe: its site is behind a Cloudflare challenge, and a Cloudflare solver is deferred;
> - HentaiHaven: its video host was unreachable.
>
> Filters and preferences round-trip through the API: a filtered search works on all 18 with filters, and a preference change on 19 of 20. **9 of the 10 most-downloaded Keiyoushi manga extensions** read down to a page image ([docs/research/manga-compat.md](docs/research/manga-compat.md)); comix needs a real WebView, which is deferred with the Cloudflare solver. Uninstalling an extension frees its code: of the 20 anime extensions, 1 is still kept after uninstall, by a video connection of its own.
>
> The app has no screens yet beyond a placeholder home that shows the backend's version and address (Phase 3), and videos don't play yet (Phase 4). See the [Roadmap](#roadmap).

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
| [`app/`](app/) | Tauri 2 shell (Rust, `src-tauri/`: backend sidecar lifecycle) + Vue 3 UI (`src/`: the startup splash and error screens, design tokens, a placeholder home) | Phase 3 in progress |
| [`server/`](server/) | Kotlin/JVM backend (Madoyomi-Server): extension host, API, DB. A fork of Suwayomi-Server at `7b061be`, rebranded and running in sidecar mode. Every change to upstream is listed in [server/UPSTREAM.md](server/UPSTREAM.md). Modules are listed below. | Phases 1 and 2 done |
| `player/` | mpv config, scripts, IPC contract | Planned (Phase 4) |
| [`spikes/`](spikes/) | Phase 0 throwaway code: `index.pb` parser, Suwayomi GraphQL smoke test, anime extension harness, mpv playback check, Tauri sidecar | Done (Phase 0) |
| [`tools/`](tools/) | Dev scripts (tracked): `fetch-upstream-assets.ps1`, `fetch-test-apks.ps1`, `run-server.ps1`, `smoke-api.ps1` (the end-to-end API test against real repos) and its helpers in `lib/MadoyomiServer.ps1`, and `compat-test/` (the top-20 anime and manga-sample lists, the static compatibility scan, the compatibility harness `run-compat.ps1` for anime and manga with its triage file, and `lib/HprofLoaderRoots.java`, a heap-dump reader that shows what keeps an uninstalled extension in memory). Local binaries (`tools/bin/`), test APKs (`tools/apks/`, `tools/compat-test/apks/`) and upstream checkouts (`tools/upstream/`) are gitignored. | Active |
| [`docs/`](docs/) | Project plan and per-phase checklists | Active |

The backend in `server/` is a Gradle multi-module project:

| Module | What |
|---|---|
| `api` | Entry point (`madoyomi.api.MainKt`), `/health`, the API-token filter, Madoyomi's GraphQL additions (trust, extension events, and the anime API: sources, browse, details and episodes, videos, filters and preferences, compat issues), the anime thumbnail route, and the integration tests of the whole API. Builds the server jar. |
| `core` | DB and migrations (manga and anime tables), the repo manager (JSON and protobuf indexes, repo trust), extension install/update/uninstall with the signing-certificate trust gate, the anime extension loader and anime services, the extension compat log, manga domain, settings, the GraphQL API (type-aware for anime and manga) and the error model, the Javalin web server, startup |
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
| [mpv](https://mpv.io/installation/) | Video playback |
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
> - **Crashes.** If the backend exits or stops answering, the app shows an overlay and restarts it after 1, 2, 4 and 8 s. After 5 failed starts in a row it stops and offers Retry and Open logs. A missing jar shows that error at once.
> - **Logs.** `sidecar.log` (the backend's output plus the shell's start, port, health and exit lines) and the server's `madoyomi.log` are in `<data dir>\logs`.
> - **Environment overrides,** set before `pnpm tauri dev`: `MADOYOMI_SIDECAR_JAR` (another jar), `MADOYOMI_JAVA` (another `java.exe`), `MADOYOMI_DATA_DIR` (another data dir, e.g. a fresh one for testing).
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
| `pnpm lint` / `pnpm lint:fix` | ESLint check / auto-fix |
| `pnpm format` / `pnpm format:check` | Prettier write / check |
| `pnpm typecheck` | Type-check with `vue-tsc` |
| `pnpm build` | Type-check and build the UI into `dist/` |
| `pnpm tauri build` | Build NSIS + MSI installers into `src-tauri/target/release/bundle/` |
| `cargo test` / `cargo clippy --all-targets` (in `app/src-tauri/`) | Rust shell unit tests (port line, backoff, `/health`, log timestamps) / Rust lints |

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
| 3 | **Frontend MVP:** Browse, Details, Library, Extensions, Settings screens; generic filter and preference renderers | 🟡 In progress (Stage 1 of 7 done: the app starts the real backend with a token and data dir, shows a startup splash, and restarts it after a crash; screens next) | [phase-3-frontend-mvp.md](docs/phases/phase-3-frontend-mvp.md) |
| 4 | **Playback:** mpv launcher, IPC, progress sync, video picker, next episode | ⚪ Not started | [phase-4-playback.md](docs/phases/phase-4-playback.md) |
| 5 | **Manga reader:** image proxy and cache, paged, webtoon and double-page modes | ⚪ Not started | [phase-5-manga-reader.md](docs/phases/phase-5-manga-reader.md) |
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

Decided so far in Phase 3 (see [phase-3-frontend-mvp.md §9](docs/phases/phase-3-frontend-mvp.md#9-decision-record)):

- **UI components: headless primitives with our own styles.** [Reka UI](https://reka-ui.com/) provides accessible dialogs, selects, tabs, checkboxes and switches. They are styled only through Madoyomi's design tokens, and long lists are virtualized with TanStack Virtual.
- **Dark and light themes from the start.** The app follows the Windows setting, and Settings will offer System, Dark or Light.
- **A Windows 11 / Fluent-like look:** Segoe UI Variable, neutral layered surfaces, small radii, subtle borders, one accent colour, compact density.
- **The app runs the backend itself.** It gets a new API token every session, which is never logged. Data lives in `%APPDATA%\Madoyomi`, the same place a server started by hand uses. The shell waits patiently while the JVM starts. After a crash it restarts the backend with a backoff (1, 2, 4, 8 s), and after 5 failed starts in a row it stops and offers Retry. Logs are in `%APPDATA%\Madoyomi\logs`.

Still open:

- mpv in a separate window (MVP) or embedded in the app window?
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
