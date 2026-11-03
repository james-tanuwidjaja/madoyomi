# Madoyomi

A Windows 11 desktop app for anime and manga that runs existing **Aniyomi** and **Mihon** extensions unchanged.

The name comes from 窓 *mado* ("window") + 読み *yomi* ("reading").

> [!WARNING]
> **Early development. Not usable yet.** Phase 0 (spikes) is done and **Phase 1 (backend foundation)** is in progress. So far there is the Tauri + Vue app shell, the throwaway spikes, and an unmodified import of Suwayomi-Server in `server/`, which has not been rebranded yet. See the [Roadmap](#roadmap).

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
| [`app/`](app/) | Tauri 2 shell (Rust, `src-tauri/`) + Vue 3 UI (`src/`) | Scaffolded |
| [`server/`](server/) | Kotlin/JVM backend: extension host, API, DB. A fork of Suwayomi-Server, imported unmodified at `7b061be` (see [server/UPSTREAM.md](server/UPSTREAM.md)). Rebrand, module split and anime domain are next. | In progress (Phase 1) |
| `player/` | mpv config, scripts, IPC contract | Planned (Phase 4) |
| [`spikes/`](spikes/) | Phase 0 throwaway code: `index.pb` parser, Suwayomi GraphQL smoke test, anime extension harness, mpv playback check, Tauri sidecar | Done (Phase 0) |
| [`tools/`](tools/) | Dev scripts (tracked), such as `fetch-upstream-assets.ps1`. Local binaries (`tools/bin/`), test APKs (`tools/apks/`) and upstream checkouts (`tools/upstream/`) are gitignored. | Active |
| [`docs/`](docs/) | Project plan and per-phase checklists | Active |

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
| JDK 21 ([Temurin](https://adoptium.net/)) | Runs the Kotlin backend and the spikes. The spikes expect it in `tools/bin/jdk-21` |
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

To work on the UI only, run `pnpm dev` and open <http://localhost:1420> in a browser. Tauri APIs are not available in this mode.

### Backend (`server/`)

`server/` is a Suwayomi-Server fork. Upstream's binary assets (Noto fonts for the Android `Typeface` stub, and test images) are not committed. Restore them once after cloning, before the first Gradle build:

```powershell
.\tools\fetch-upstream-assets.ps1
```

The script copies each file from `tools/upstream/Suwayomi-Server` when that checkout is at the pinned commit. Otherwise it downloads the file from GitHub. Either way it checks the file's SHA-256. Build and run instructions for the Madoyomi server come later in Phase 1.

> [!NOTE]
> `pnpm tauri dev` also starts a JVM sidecar. For now it is the Phase 0 dummy backend (`spikes/sidecar`), not the real server. Build it once with `cd spikes; .\gradlew :sidecar:shadowJar` (needs JDK 21 in `tools/bin/jdk-21`, or set `MADOYOMI_JAVA`). Without it the app still opens, and the home screen shows "Backend: error". See [spikes/README.md](spikes/README.md).

## Common commands

Run these from `app/`:

| Command | What it does |
|---|---|
| `pnpm tauri dev` | Run the desktop app (Vite + Rust shell, hot reload) |
| `pnpm dev` | UI only, in a browser at http://localhost:1420 |
| `pnpm test` / `pnpm test:watch` | Run Vitest once / in watch mode |
| `pnpm lint` / `pnpm lint:fix` | ESLint check / auto-fix |
| `pnpm format` / `pnpm format:check` | Prettier write / check |
| `pnpm typecheck` | Type-check with `vue-tsc` |
| `pnpm build` | Type-check and build the UI into `dist/` |
| `pnpm tauri build` | Build NSIS + MSI installers into `src-tauri/target/release/bundle/` |

More detail is in [app/README.md](app/README.md).

## Roadmap

Status: ✅ Done · 🟡 In progress · ⚪ Not started

| Phase | Focus | Status | Details |
|---|---|---|---|
| 0 | **Spikes:** prove manga extensions, the `index.pb` index, anime extensions, mpv playback and the Tauri sidecar each work on their own | ✅ Done | [phase-0-spikes.md](docs/phases/phase-0-spikes.md) |
| 1 | **Backend foundation:** fork and rebrand Suwayomi-Server, add an anime domain, repo manager for both index formats, extension install/trust | 🟡 In progress | [phase-1-backend-foundation.md](docs/phases/phase-1-backend-foundation.md) |
| 2 | **Anime sources:** Aniyomi `animesource` API, Android stubs, filters and preferences as JSON | ⚪ Not started | [phase-2-anime-sources.md](docs/phases/phase-2-anime-sources.md) |
| 3 | **Frontend MVP:** Browse, Details, Library, Extensions, Settings screens; generic filter and preference renderers | ⚪ Not started | [phase-3-frontend-mvp.md](docs/phases/phase-3-frontend-mvp.md) |
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
- **One JVM process** for both extension types to start with. The final call is made in Phase 1.

Decided at the start of Phase 1 (to be recorded in [phase-1-backend-foundation.md §8](docs/phases/phase-1-backend-foundation.md)):

- **Plain copy of upstream** into `server/`, first committed unmodified as a baseline, with the upstream commit recorded in [server/UPSTREAM.md](server/UPSTREAM.md).
- **Keep upstream package names** (`suwayomi.tachidesk.*`). New code goes under `madoyomi.*`, which keeps upstream merges manageable.
- **Keep H2** as the database.
- **Mihon trust model:** adding a repo trusts its signing key. Any other certificate needs explicit trust, and untrusted extensions are never loaded.

Still open:

- mpv in a separate window (MVP) or embedded in the app window?
- Windows-only, or keep macOS/Linux possible?

## Contributing

The project is private and solo for now. The workflow:

1. Read [docs/PLAN.md](docs/PLAN.md) and the relevant phase document before starting work.
2. Tick checklist items in `docs/phases/` only when they are done **and verified**.
3. Before opening a PR, run `pnpm lint`, `pnpm typecheck` and `pnpm test` in `app/`.
4. Work on a branch and open a pull request into `main`.
5. Never commit APKs, `.dex` files, tool binaries, or the upstream fonts and test images restored by `tools/fetch-upstream-assets.ps1`.

## Acknowledgements

- [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) (MPL-2.0): the backend base and the manga extension host.
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) and [Mihon](https://github.com/mihonapp/mihon) (Apache-2.0): the extension APIs Madoyomi implements.
- [mpv](https://mpv.io/) for playback and [Tauri](https://tauri.app/) for the desktop shell.

## Disclaimer

Madoyomi does not host, ship or provide any content or extensions. Users add extension repositories themselves and are responsible for the sources they use. Madoyomi is not affiliated with Aniyomi, Mihon, Suwayomi, or any extension or content provider.

## License

[MPL-2.0](LICENSE). Files derived from upstream projects keep their original license headers. `server/` is a fork of Suwayomi-Server (MPL-2.0); see [server/UPSTREAM.md](server/UPSTREAM.md).
