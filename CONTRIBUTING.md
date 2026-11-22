# Contributing to Madoyomi

Thanks for helping. Madoyomi is a Windows desktop app (Tauri 2 + Vue 3) with a Kotlin/JVM backend forked from Suwayomi-Server, and mpv for playback. This page covers where to start, how pull requests work, and how to build and test everything.

## Where to start

- **Found a bug, or want a feature?** Open an issue with one of the [issue forms](https://github.com/james-tanuwidjaja/madoyomi/issues/new/choose). For a bug, attach the zip from **Settings → Backend → Export logs**.
- **A question, or a bigger idea** (a new platform, a large feature, a change of direction)? Start in [Discussions](https://github.com/james-tanuwidjaja/madoyomi/discussions): Q&A for questions, Ideas for plans. Agreeing on the approach there first saves a pull request that can't be merged.
- **A security problem?** Don't open an issue: see [SECURITY.md](SECURITY.md).
- **Want to write code?** Look for an open issue, or comment on one to say you're on it. Small fixes can go straight to a pull request.

## Ground rules

- **No extension repos or content.** Madoyomi ships with no repos and no extensions, and never links to or provides them: not in code, tests, docs, issues or pull requests. Tests use local fixtures or the URLs a script already takes as a parameter.
- **No binaries in git:** no APKs, `.dex` files, jars (except the Gradle wrapper's), `.pb` files, tool binaries, fonts or test images (restored by `tools/fetch-upstream-assets.ps1`), installers, logs, zips or heap dumps. `.gitignore` covers the usual places.
- **Upstream code:** when you change a file that came from Suwayomi-Server (or Mihon/Aniyomi), mark the edit with a `// Madoyomi:` comment and list it in [server/UPSTREAM.md](server/UPSTREAM.md). New code goes under the `madoyomi.*` packages.
- **License:** contributions are under the project's license, [MPL-2.0](LICENSE). Keep the license headers of files derived from upstream projects.

## Branches and pull requests

`main` only takes pull requests:

1. Fork the repo (or, as a collaborator, push a branch) and branch off an up-to-date `main`, e.g. `fix/reader-zoom` or `feat/linux-paths`.
2. Make the change with tests where it can have them, and run the checks in [Before you open a pull request](#before-you-open-a-pull-request).
3. Open a pull request into `main` and fill in the template: what changed, how you tested it, the linked issue.
4. A pull request needs **one approval from a code owner** ([CODEOWNERS](.github/CODEOWNERS)) and **every review conversation resolved**. It is merged with a merge commit (no squash or rebase merges); force pushes to `main` and deleting it are blocked.
5. **CI on forks:** workflows of a first-time or external contributor's pull request run only after the maintainer approves them, and with read-only permissions.

## Releases

Tags, releases and version bumps are the maintainer's. Leave `VERSION` and its copies alone in a pull request; a user-facing change can suggest a line for [CHANGELOG.md](CHANGELOG.md). The release checklist is [RELEASING.md](RELEASING.md).

## Development

### How it works

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

Every change to upstream Suwayomi-Server is listed in [server/UPSTREAM.md](server/UPSTREAM.md), with the backend's startup contract (the arguments, environment and endpoints the shell relies on). The contract between the backend and mpv is [player/IPC.md](player/IPC.md).

### Repository layout

| Path | What |
|---|---|
| [`app/`](app/) | Tauri 2 shell (Rust, `src-tauri/`: backend sidecar lifecycle) + Vue 3 UI (`src/`: the app shell and routes; the Repos, Extensions, Browse, source, Details, Library and Settings screens; the filter and preference renderers; the typed API client and WebSocket in `api/`; vue-query hooks in `composables/`; the startup splash and error screens; design tokens; the video picker, live progress and bulk marks on episodes, the player settings; the manga reader. See [app/README.md](app/README.md)) |
| [`server/`](server/) | Kotlin/JVM backend (Madoyomi-Server): extension host, API, DB. A fork of Suwayomi-Server at `7b061be`, rebranded and running in sidecar mode. Every change to upstream is listed in [server/UPSTREAM.md](server/UPSTREAM.md). Modules are listed below. |
| [`player/`](player/) | mpv's config dir for Madoyomi (`mpv.conf`, `input.conf`) and the contract between the backend and mpv ([IPC.md](player/IPC.md)) |
| [`spikes/`](spikes/) | Early throwaway code: `index.pb` parser, Suwayomi GraphQL smoke test, anime extension harness, mpv playback check, Tauri sidecar |
| [`tools/`](tools/) | Dev scripts (tracked): `fetch-upstream-assets.ps1`, `fetch-test-apks.ps1`, `run-server.ps1`, the release scripts (`jlink.ps1` with its runtime check `lib/JreProbe.java`, `fetch-binaries.ps1` with the pins and license texts in `binaries.json`, `build-release.ps1`, and `version.ps1`, which keeps `VERSION` and its copies in step; `check-release.ps1`, `verify-updater.mjs`, `release-notes.mjs` and `scan-secrets.mjs`, used by the release workflow in `.github/workflows/`; `lib/changelog.mjs`, the CHANGELOG section `release-notes.mjs` reads, tested in the app's suite), `smoke-api.ps1` (the end-to-end API test against real repos) and its helpers in `lib/MadoyomiServer.ps1`, and `compat-test/` (the top-20 anime and manga-sample lists, the static compatibility scan, the compatibility harness `run-compat.ps1` for anime and manga with its triage file and its reports in `compat-test/reports/`, and `lib/HprofLoaderRoots.java`, a heap-dump reader that shows what keeps an uninstalled extension in memory). Local binaries (`tools/bin/`), test APKs (`tools/apks/`, `tools/compat-test/apks/`) and upstream checkouts (`tools/upstream/`) are gitignored, and so is what a release bundles (`app/src-tauri/resources/`). |

The backend in `server/` is a Gradle multi-module project:

| Module | What |
|---|---|
| `api` | Entry point (`madoyomi.api.MainKt`), `/health`, the API-token filter, Madoyomi's GraphQL additions (trust, extension events, the anime API: sources, browse, details and episodes, videos, filters and preferences, compat issues; and playback), the anime thumbnail route, and the integration tests of the whole API. Builds the server jar. |
| `core` | DB and migrations (manga and anime tables), the repo manager (JSON and protobuf indexes, repo trust), extension install/update/uninstall with the signing-certificate trust gate, the anime extension loader and anime services, the mpv player (`madoyomi.core.player`: finding mpv, its command line, the playback session, the JSON IPC client, progress and the watched mark), the extension compat log, manga domain, settings, the GraphQL API (type-aware for anime and manga) and the error model, the Javalin web server, startup |
| `ext-manga` | Mihon manga source API (`eu.kanade.tachiyomi.source`) |
| `ext-anime` | Aniyomi anime source API (`eu.kanade.tachiyomi.animesource`), ported from Aniyomi (lib 17, which keeps every lib-14/16 member). Trusted anime extensions run on it. |
| `ext-host` | What extensions run on: OkHttp network stack, helpers, the dex2jar bytecode fixers (including a repair for R8-optimised APKs), the extension classloader, APK inspection and signature verification (apksig) |
| `AndroidCompat` | Android API stubs that extensions call |
| `server-config`, `server-config-generate`, `i18n` | Settings, generated settings code, translations |

Dependencies run one way: `api → core → {ext-manga, ext-anime} → ext-host → {AndroidCompat, server-config}`. Package names stay as upstream's (`suwayomi.tachidesk.*`, `eu.kanade.tachiyomi.*`). New code goes under `madoyomi.*`.

### Prerequisites

Development targets **Windows 11**.

#### Required (app shell + UI)

| Tool | Version | Notes |
|---|---|---|
| [Node.js](https://nodejs.org/) | 22 LTS or newer | Needed by Vite 8 |
| [pnpm](https://pnpm.io/installation) | latest | `corepack enable` or `npm install -g pnpm` |
| [Rust](https://rustup.rs/) (via rustup) | stable, 1.85 or newer | The crate uses Rust edition 2024 |
| [Microsoft C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) | latest | Select the **Desktop development with C++** workload |
| [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) | latest | Already installed on Windows 11 |

The full list is in Tauri's guide: [Tauri prerequisites for Windows](https://v2.tauri.app/start/prerequisites/).

#### Needed for the backend

| Tool | Notes |
|---|---|
| JDK 21 ([Temurin](https://adoptium.net/)) | Builds and runs the Kotlin backend (and the spikes). The scripts expect it in `tools/bin/jdk-21` (the release scripts also take `MADOYOMI_JDK` or `JAVA_HOME` without it, as CI does). A release build links its runtime from this JDK, so it must be a full JDK with `jmods` (Temurin's is) |
| [IntelliJ IDEA](https://www.jetbrains.com/idea/) | Recommended IDE for `server/` (Community edition is enough) |
| [dex2jar](https://github.com/ThexXTURBOXx/dex2jar), [jadx](https://github.com/skylot/jadx) | Convert and inspect extension APKs |
| [mpv](https://mpv.io/installation/) | Video playback. The app finds `mpv.exe` on PATH, or `tools/bin/mpv/mpv.exe`, or wherever `MADOYOMI_MPV` points. A release build brings its own (`tools/fetch-binaries.ps1`) |
| [protoc](https://github.com/protocolbuffers/protobuf/releases) | Decodes the manga repo's `index.pb` |
| [ffmpeg](https://ffmpeg.org/download.html), [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Anime downloads: ffmpeg for HLS streams, yt-dlp when it fails. Found on PATH (`winget install yt-dlp.yt-dlp` brings both), or in `tools/bin/ffmpeg/ffmpeg.exe` and `tools/bin/yt-dlp/yt-dlp.exe`, or where `MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` or Settings → Downloads point. A release build brings its own (`tools/fetch-binaries.ps1`) |

Put these tools in `tools/bin/`. That folder is gitignored, so binaries are never committed. APKs go in `tools/apks/` and are also never committed.

#### Editor

[VS Code](https://code.visualstudio.com/) is set up for the repo. When you open the folder it offers to install the recommended extensions from [`.vscode/extensions.json`](.vscode/extensions.json) (Vue, ESLint, Prettier, Vitest, Tauri, rust-analyzer, Kotlin, Gradle, and others).

#### Check your setup

```powershell
node -v
pnpm -v
rustc -V
cargo -V
java -version   # only needed for backend/spike work
```

### Getting started

```powershell
git clone https://github.com/james-tanuwidjaja/madoyomi.git
cd madoyomi/app
pnpm install
pnpm tauri dev
```

`pnpm tauri dev` starts the Vite dev server and opens the desktop window. The first run compiles the Rust shell and takes a few minutes. Later runs are much faster.

To work on the UI only, run `pnpm dev` and open <http://localhost:1420> in a browser. Tauri APIs are not available in this mode, so there is no backend, and a banner says so. To use a backend anyway, start one with `.\tools\run-server.ps1` and put the base URL and token it prints into `app/.env.local` (gitignored) as `VITE_MADOYOMI_URL=http://127.0.0.1:<port>` and `VITE_MADOYOMI_TOKEN=<token>`, then restart `pnpm dev`.

#### Backend (`server/`)

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

`build` runs the ktlint check and all tests. If ktlint reports style violations, `.\gradlew.bat ktlintFormat` fixes most of them; check `git diff` afterwards. The server jar is `server/api/build/Madoyomi-Server-v<VERSION>.jar` (built by `.\gradlew.bat :api:shadowJar`).

Run it locally the way the app will start it:

```powershell
.\tools\run-server.ps1            # builds the jar, then runs it
.\tools\run-server.ps1 -NoBuild   # runs the last built jar
```

The script generates an API token, starts the server with a dev data dir in `tools/dev-data/` (gitignored), and prints the token. Once the server prints `MADOYOMI_PORT=<port>`, the script prints the base URL and example requests. Every request except `GET /health` needs `Authorization: Bearer <token>`. Stop it with Ctrl+C. The full startup contract (arguments, environment, endpoints) is in [server/UPSTREAM.md](server/UPSTREAM.md#startup-contract-what-the-tauri-shell-relies-on).

End-to-end smoke test against real extension repos (needs internet access):

```powershell
.\tools\smoke-api.ps1            # builds the jar, then runs the test
.\tools\smoke-api.ps1 -NoBuild   # tests the last built jar
```

The script starts the jar the way the app does, on a throwaway data dir under `%TEMP%`, and drives it over GraphQL only. It adds an anime and a manga repo (the script's defaults, or `-AnimeRepo` and `-MangaRepo`), lists both kinds' extensions, and installs and checks one extension of each kind while a WebSocket subscription follows the installs. It then reads a manga chapter down to a page image, and runs the anime source from popular through details and episodes to the videos of the newest episode, a resolved video and a thumbnail. It prints PASS/FAIL per step and exits non-zero if any step fails. `Get-Help .\tools\smoke-api.ps1 -Detailed` lists the options (another jar, data dir or repo URLs).

> [!NOTE]
> `pnpm tauri dev` also starts the real backend, the newest `server\api\build\Madoyomi-Server-*.jar`. Build it once first: `.\gradlew.bat :api:shadowJar` in `server/` (see [Backend](#backend-server)). The shell runs it with `tools/bin/jdk-21`, a fresh API token per app session and `--data-dir %APPDATA%\Madoyomi`, and keeps its stdin open; the backend exits when the app does.
> - **Startup.** The window shows "Starting the backend…" until `/health` answers, about 10–12 s (longer on a busy machine; after 30 s it says so). The shell keeps waiting for up to 5 minutes while the JVM is alive.
> - **Crashes.** If the backend exits or stops answering, the app shows an overlay and restarts it after 1, 2, 4 and 8 s. After 5 failed starts in a row it stops and offers Retry and Open logs. A missing jar shows that error at once, and Retry looks for it again, so building the jar and pressing Retry is enough. Once the backend is back (on a new port), the UI's requests and its WebSocket move there by themselves, and the sidebar's footer shows "Live updates" again.
> - **Logs.** `sidecar.log` (the backend's output plus the shell's start, port, health and exit lines) and the server's `madoyomi.log` are in `<data dir>\logs`.
> - **mpv.** Play needs mpv. A path set in Settings → Player comes first. Otherwise the shell passes the backend `tools/bin/mpv/mpv.exe` if it's there, and without it the backend uses `mpv.exe` from PATH. It runs mpv with the repo's `player/` as its config dir. mpv's output is in `<data dir>\logs\mpv.log`, and `sidecar.log` says which mpv and config dir the backend was given. The backend drives mpv over `\\.\pipe\madoyomi-mpv-<session>`; mpv accepts several clients, so a script can read its properties there too.
> - **Environment overrides,** set before `pnpm tauri dev`: `MADOYOMI_SIDECAR_JAR` (another jar), `MADOYOMI_JAVA` (another `java.exe`), `MADOYOMI_DATA_DIR` (another data dir, e.g. a fresh one for testing), `MADOYOMI_MPV` (another `mpv.exe`), `MADOYOMI_PLAYER_DIR` (another mpv config dir).
> - **One server per data dir.** Don't run `pnpm tauri dev` and a server of your own on the same data dir at once (the database is locked). `tools/run-server.ps1` uses `tools/dev-data/`, so the two don't clash.

### Common commands

Run these from `app/`:

| Command | What it does |
|---|---|
| `pnpm tauri dev` | Run the desktop app (Vite + Rust shell, hot reload). It starts the backend jar from `server/api/build` (see the note above) |
| `pnpm dev` | UI only, in a browser at http://localhost:1420 (no backend, unless `VITE_MADOYOMI_URL` and `VITE_MADOYOMI_TOKEN` are set in `.env.local`) |
| `pnpm test` / `pnpm test:watch` | Run Vitest once / in watch mode |
| `pnpm vitest run src/api/__tests__/operations.live.spec.ts` | Check the UI's GraphQL operations against a running server: validates every document against its schema, checks the token, receives a WebSocket event, runs a filtered search on an installed source of each kind (it needs the source's site), refreshes an entry and puts it in the library and out, changes a preference of a configurable source of each kind and restores it, reads a manga chapter's pages through the backend with a prefetch, then saves and restores its progress, sets, reads and removes a manga's reader settings in its meta, and creates, renames, moves, assigns and deletes categories of each kind, and checks that an entry added to the library goes into a category flagged for new entries, and for each kind saves a stored episode's or chapter's position again, finds it first in the history, reads the library's counts and deletes that entry's history, leaving its marks, and for each kind checks a temporary category holding a stored entry for new items, follows the check over the WebSocket to its end, and reads the Updates and the marks, and downloads a chapter as a CBZ through the queue (pausing, moving, resuming and cancelling a second one), follows it over the WebSocket, reads its pages back and deletes it, and checks ffmpeg and yt-dlp, then downloads an HLS episode (ffmpeg) and a direct-file episode through the queue at the lowest preferred quality, follows them over the WebSocket, checks their marks and deletes them (minutes, with the sites), and backs up, reads the file back over REST and sends it back as a picked file, restores it with merge over the WebSocket and checks nothing was lost, and reads the backend's diagnostics for Export logs (no token, the library only when asked) and moves the downloads folder to a new empty folder and back, after a refused one. Set `MADOYOMI_LIVE_URL` and `MADOYOMI_LIVE_TOKEN` (e.g. from `tools/run-server.ps1`) first; otherwise these tests are skipped, as in `pnpm test` |
| `pnpm lint` / `pnpm lint:fix` | ESLint check / auto-fix |
| `pnpm format` / `pnpm format:check` | Prettier write / check |
| `pnpm typecheck` | Type-check with `vue-tsc` |
| `pnpm build` | Type-check and build the UI into `dist/` |
| `pnpm tauri build` | Build NSIS + MSI installers into `src-tauri/target/release/bundle/` (the installer settings are in `tauri.conf.json`, the uninstaller's "remove data" in `src-tauri/installer/`), **without** the bundled Java runtime, jar and tools: for a release use `..\tools\build-release.ps1` (below) |
| `cargo test` / `cargo clippy --all-targets` (in `app/src-tauri/`) | Rust shell unit tests (port line, backoff, `/health`, log timestamps, where java, the jar, mpv, ffmpeg and yt-dlp are found in dev and release builds; `sidecar.log`'s rolling and cap, the crash count, the UI's log lines, and Export logs' zip: what goes in, the token and the user name taken out; the app updater's settings, its quiet and shown failures, a skipped version, and the install's order: download, stop the backend, then the installer; `--data-dir`) / Rust lints |

More detail is in [app/README.md](app/README.md).

Backend commands. Run the Gradle ones from `server/`, with `JAVA_HOME` set to `tools/bin/jdk-21`:

| Command | What it does |
|---|---|
| `.\gradlew.bat build` | Compile all modules, run ktlint and all tests |
| `.\gradlew.bat :core:test` | Run the server tests only |
| `.\gradlew.bat :api:shadowJar` | Build `server/api/build/Madoyomi-Server-v<VERSION>.jar` (the version in `VERSION`) |
| `.\gradlew.bat ktlintFormat` | Reformat Kotlin sources |
| `.\tools\run-server.ps1` (from the repo root) | Build and run the server with a dev data dir and a fresh token |
| `.\tools\smoke-api.ps1` (from the repo root) | End-to-end API test against real extension repos (`-AnimeRepo`, `-MangaRepo`). `-Java <java.exe>` runs the server on another runtime (e.g. the bundled `app\src-tauri\resources\jre\bin\java.exe`) |
| `.\tools\build-release.ps1` (from the repo root) | A release build: checks the version (`tools/version.ps1`; a mismatch stops it), builds the server jar, links the runtime, fetches the pinned mpv, ffmpeg and yt-dlp with their license texts into `app/src-tauri/resources/` (gitignored), then runs `pnpm tauri build` with `src-tauri/tauri.release.conf.json`. Output: the exe with them beside it in `app/src-tauri/target/release/`, and the per-user NSIS installer and the MSI in `target/release/bundle/` (about 8–15 min). `-NoJar` uses the last built jar (it must be this version's), `-NoBundle` skips the installers, `-SkipTauri` stops after the resources. The NSIS installer is signed for the updater (its `.sig` beside it) and `latest.json` written into `bundle/nsis/`, which needs the private key in `TAURI_SIGNING_PRIVATE_KEY` (its file or its text) and its password in `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, never in the repo; `-NoUpdater` builds without them (contributors always build this way; the key is the maintainer's, see [RELEASING.md](RELEASING.md#the-updaters-key)). `-Notes <text>` or `-NotesFile <file>` are the release notes the update dialog shows, `-DownloadUrl` the installer's URL in `latest.json` (default: the GitHub release's asset), `-Config <file>` another Tauri config merged last (a test feed; never for a release) |
| `.\tools\version.ps1` (from the repo root) | Check that `VERSION` and its copies (`app/package.json`, `tauri.conf.json`, `Cargo.toml`, `Cargo.lock`, the server's `Constants.kt`) match; `-Set X.Y.Z` changes the version everywhere, `-Sync` writes the copies from `VERSION`, `-SelfTest` tests the script on a temporary copy. Only a numeric X.Y.Z (the MSI's limits) |
| `.\tools\check-release.ps1 -Dir <folder>` (from the repo root) | Check a release's files (CI runs it, and `RELEASING.md` on the draft's downloaded assets): the NSIS installer, its `.sig`, the MSI and `latest.json` are there; the installers' versions; the MSI's upgrade code; `latest.json` and its signature (`verify-updater.mjs`); `SHA256SUMS.txt` (`-WriteSums` writes it). `-Version`, `-Repo` |
| `node tools/verify-updater.mjs <latest.json> <installer>` (from the repo root) | Check `latest.json` (version, notes, date, both platforms, the published URL) and its updater signature against the public key in `tauri.conf.json` (Ed25519 over the installer's BLAKE2b-512, the trusted comment). `--version`, `--repo`, `--url-any` (a test feed) |
| `node tools/release-notes.mjs [X.Y.Z]` (from the repo root) | Print the version's section of `CHANGELOG.md` (fails without one or with an empty one; the section is read by `tools/lib/changelog.mjs`, whose test runs with `pnpm test`); `--notes <file>` writes it for `latest.json`, `--body <file>` the GitHub release's text |
| `node tools/scan-secrets.mjs --env NAME … <paths>` (from the repo root) | Look for the values of those environment variables (the updater's key and its password, in CI) in files and folders; prints only where they were found |
| **Release workflow** (GitHub Actions, [`.github/workflows/release.yml`](.github/workflows/release.yml)) | Push a tag `vX.Y.Z` (equal to `VERSION`): every offline test, the signed release build, the checks above, then a draft release with the installers, `latest.json` and `SHA256SUMS.txt`. Actions → Release → Run workflow: the same without a release (a dry run). See [RELEASING.md](RELEASING.md) |
| `.\tools\jlink.ps1` (from the repo root) | Link the Java runtime a release bundles into `app/src-tauri/resources/jre` from `tools/bin/jdk-21` (else `MADOYOMI_JDK` or `JAVA_HOME`, as in CI): jdeps' modules on the server jar plus the ones extensions reach at runtime (each with its reason in the script), checked by `tools/lib/JreProbe.java` on the new runtime; fails over 60 MB. `-ExtraModules` adds one |
| `.\tools\fetch-binaries.ps1` (from the repo root) | Download the mpv, ffmpeg and yt-dlp builds pinned in `tools/binaries.json` and their license texts (cached in `tools/bin/downloads`), check their SHA-256 (a mismatch fails) and unpack them into `app/src-tauri/resources/<tool>` (mpv's libraries' licenses in `mpv/licenses/`). `-Only`, `-Force`, `-Out tools\bin` (where a dev build looks) |
| `.\tools\fetch-upstream-assets.ps1` (from the repo root) | Restore the upstream fonts and test images |
| `.\tools\fetch-test-apks.ps1` (from the repo root) | Download the extension APKs the tests use into `tools/apks/` |
| `.\tools\compat-test\scan.ps1` (from the repo root) | Download the top-20 anime and manga-sample APKs and run the static compatibility scan into [`tools/compat-test/reports/compat-scan.md`](tools/compat-test/reports/compat-scan.md) |
| `.\tools\compat-test\run-compat.ps1 -NoBuild` (from the repo root) | Compatibility harness (`-Java <java.exe>` runs it on another runtime, e.g. the jlink one, with `-Report` for the output): run the top 20 anime extensions on the real server jar (about 8 min, needs internet access), with process health, into [`tools/compat-test/reports/anime-compat.md`](tools/compat-test/reports/anime-compat.md). Per extension it also round-trips a preference and runs a filtered search, and after uninstalling all of them it reports what still keeps their code in memory (a heap dump, read by `lib/HprofLoaderRoots.java`). `-Only <names>` reruns a few into `tools/compat-test/out/`; `-ReportOnly` redraws the report after a `triage.txt` edit |
| `.\tools\compat-test\run-compat.ps1 -NoBuild -Kind Manga` (from the repo root) | The same harness over the 10-extension manga sample (popular, details and chapters, pages, a page image) into [`tools/compat-test/reports/manga-compat.md`](tools/compat-test/reports/manga-compat.md): the "no regression in manga" check (about 2 min) |

## Before you open a pull request

From `app/`:

```powershell
pnpm lint
pnpm typecheck
pnpm format:check
pnpm test
pnpm build
```

From `app/src-tauri/` (for changes to the Rust shell):

```powershell
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

From `server/`, with `JAVA_HOME` set to `tools/bin/jdk-21` (for backend changes): `.\gradlew.bat build` (ktlint and every test). Tests that need the extension APKs skip without `tools/apks/` (`.\tools\fetch-test-apks.ps1` downloads them).

For a version change: `.\tools\version.ps1` from the repo root. CI runs all of these on a release tag (see [RELEASING.md](RELEASING.md)). The live checks (`tools/smoke-api.ps1`, the live spec and the compatibility harness) need real sites and internet access, so they aren't part of CI; run the ones that cover what you changed.

## Compatibility reports

[`tools/compat-test/reports/`](tools/compat-test/reports/) holds the latest results of the static scan (`compat-scan.md`) and of the harness for anime (`anime-compat.md`) and manga (`manga-compat.md`). They are rewritten by `tools/compat-test/scan.ps1` and `run-compat.ps1`; a partial rerun (`-Only`) goes to the gitignored `tools/compat-test/out/` instead. Commit a report only from a full run.
