# Madoyomi — Project Plan

**Madoyomi** (窓 *mado* "window" + 読み *yomi* "reading") is a Windows 11 desktop app for anime + manga that runs **existing Aniyomi/Mihon extensions** unchanged.

- Repo: `madoyomi` (private for now, open source later)
- License: **MPL-2.0**

- Anime repo: `https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json` (Aniyomi APKs)
- Manga repo: `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb` (Mihon APKs, protobuf index)

Repos are **user-added** (like Aniyomi): the app ships with no repos or extensions.

---

## 1. Architecture

```
┌───────────────────────────── Madoyomi · Tauri 2 shell ─────────────────────────────┐
│  Vue 3 UI (WebView2)                                                               │
│    library · browse · details · manga reader · settings · downloads                │
│        │  HTTP (REST/GraphQL) + WebSocket events  →  127.0.0.1:<random port>       │
│        ▼                                                                           │
│  Sidecar: Kotlin/JVM backend (bundled jlink JRE)                                   │
│    ├─ Extension host: APK → dex2jar → JVM classloader + Android API stubs          │
│    │     ├─ Mihon source API   (manga)  — reuse Suwayomi                           │
│    │     └─ Aniyomi animesource API (anime) — new                                  │
│    ├─ Repo manager (JSON + protobuf indexes), install/update/trust                 │
│    ├─ Library DB, history, updates checker, download queue                         │
│    ├─ Image proxy (applies source headers/cookies)                                 │
│    └─ WebView/Cloudflare shim (headless browser)                                   │
│                                                                                    │
│  Sidecar: mpv.exe  ← JSON IPC (named pipe) driven by backend or Tauri              │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Why this split:
- Extensions are Android/Kotlin bytecode, so they need a JVM host. That makes Kotlin non-negotiable for the backend.
- The UI is Vue because that's the existing skill set; Tauri keeps the shell small.
- mpv handles MKV/HEVC/ASS subs/HLS with custom headers, which a webview `<video>` can't do reliably.

---

## 2. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Shell | Tauri 2 | Window, sidecar lifecycle, auto-update, installer (NSIS/MSI) |
| UI | Vue 3 + Vite + TS, Pinia, Vue Router, TanStack Query (vue-query) | Virtualized lists for big libraries |
| Backend | Kotlin/JVM (base on **Suwayomi-Server**, MPL-2.0) | Keep its web framework/DB layer if forking |
| Extension loading | dex2jar + custom classloader + Android stubs | Already solved for manga in Suwayomi. Madoyomi installs from the APK only, after apksig verifies it, and repairs R8-optimised dex that dex2jar mistranslates (phase-1 §10) |
| Anime API | Aniyomi `extensions-lib` (`eu.kanade.tachiyomi.animesource.*`) | Match the lib version yuzono builds against (APK names show `v14.x`) |
| Manga index parsing | `kotlinx.serialization.protobuf` | For `index.pb`; Suwayomi's Mihon DTOs are reused, plus an Aniyomi variant for anime `index.pb` (phase-1 §10) |
| HTTP | OkHttp (what extensions expect) | Shared client, cookie jar, rate limiting interceptors |
| Player | mpv (sidecar) + JSON IPC | `--http-header-fields`, `--sub-files`, `--audio-files` |
| Cloudflare / WebView | Headless browser shim (Suwayomi's approach) | Needed by a subset of extensions |
| DB | SQLite (or Suwayomi's existing DB) | Keep schema separate for anime vs manga |
| JRE | `jlink` custom runtime (~40–60 MB) | No Java install required |

---

## 3. Repo layout (monorepo)

```
madoyomi/
/app            Tauri + Vue 3 frontend
  /src-tauri    Rust shell: sidecar spawn, port handshake, updater
  /src          Vue app
/server         Kotlin backend (Suwayomi-Server fork, see server/UPSTREAM.md)
  /core         DB, library, downloads, settings, GraphQL + Javalin (kept here, see phase 1)
  /ext-host     network stack, dex2jar bytecode fixer, extension classloader
  /ext-manga    Mihon source API (from Suwayomi)
  /ext-anime    Aniyomi animesource API (ported from Aniyomi)
  /api          entry point, sidecar HTTP (/health, token), Madoyomi API additions; builds the jar
  /AndroidCompat  Android API stubs (from Suwayomi)
  /server-config, /server-config-generate, /i18n   settings + generated code, translations (from Suwayomi)
/player         mpv config, scripts (e.g. uosc), IPC client contract
/tools          jlink script, packaging, test-extension fetcher
```

---

## 4. Phases

> Detailed steps and full checklists live in [`docs/phases/`](phases/). Tick items there as work gets done, and tick the milestones below when a phase's exit criteria pass.

### Phase 0 — Spikes (de-risk first, ~1–2 weeks)
Details: [phase-0-spikes.md](phases/phase-0-spikes.md)

Goal: prove each hard part works in isolation before committing.

1. **Manga extension on JVM:** run Suwayomi-Server, add the Keiyoushi repo, install 2–3 extensions, browse + read.
2. **Protobuf index:** parse `index.pb` and work out its schema (fields → name, pkg, apk, version, sources).
3. **Anime extension on JVM:** take one yuzono APK, dex2jar it, load it against Aniyomi `extensions-lib` stubs, call `getPopularAnime` → `getEpisodeList` → `getVideoList`.
4. **Playback:** feed a returned `Video` (URL + headers + subtitle tracks) into mpv and confirm it plays.
5. **Tauri sidecar:** Tauri spawns the JVM jar, gets its port, and Vue calls it.

Exit: all five work. Decide **fork Suwayomi vs. depend on it as a library** (fork is likely more practical).

Milestones:
- [x] S1 manga via Suwayomi works
- [x] S2 `index.pb` schema documented and parsed
- [x] S3 anime extension returns videos on the JVM
- [x] S4 mpv plays a returned video with headers
- [x] S5 Tauri spawns the JVM sidecar and Vue calls it
- [x] Fork/library and REST/GraphQL decisions recorded

### Phase 1 — Backend foundation
Details: [phase-1-backend-foundation.md](phases/phase-1-backend-foundation.md)

- Fork Suwayomi-Server and rebrand to Madoyomi (keep MPL-2.0 headers and attribution on Suwayomi-derived files).
- Add an `anime` domain alongside `manga`: entities, DB tables, API namespaces.
- Repo manager handles both index formats (JSON for anime, protobuf for manga).
- Extension install/update/uninstall, with **trust prompt** by signing-cert hash (Mihon/Aniyomi model).
- Exit: install and list extensions for both types from the API.

Milestones:
- [x] `server/` fork rebranded with module layout and attribution
- [x] Anime domain tables + migrations
- [x] Repo manager parses JSON and protobuf indexes
- [x] Install / update / uninstall / trust for both types via the API
- [x] Tests pass in `./gradlew build`

### Phase 2 — Anime source support
Details: [phase-2-anime-sources.md](phases/phase-2-anime-sources.md)

- Implement the Aniyomi `animesource` interfaces and models: `AnimeCatalogueSource`, `AnimeHttpSource`, `SAnime`, `SEpisode`, `Video` (with headers, subtitle tracks and audio tracks), filters and preferences.
- Add Android stubs anime extensions commonly need (`SharedPreferences`, `Context`, `Uri`, `Base64`, `Log`, etc.).
- Expose source preferences (extension settings screens) as JSON schema for the UI.
- Exit: the top ~20 yuzono extensions browse, search, list episodes and return videos.

Milestones:
- [x] `animesource` API + models implemented (Stages 1, 3 and 6: every API-surface item of the phase doc, `ParsedAnimeHttpSource` by a test, since none of the top 20 uses it)
- [x] Android stubs + missing-class logging (Stage 2)
- [x] Filters and preferences exposed as JSON (Stages 3–4)
- [x] Anime API endpoints (browse, details, episodes, videos) (Stage 4)
- [x] Compat harness: ≥18/20 top yuzono extensions pass (18/20 on 2026-11-06, [anime-compat.md](research/anime-compat.md))

**Phase 2 done (2026-11-06).** Every exit criterion passes: 18/20 extensions, filters and preferences through the API, no manga regression ([manga-compat.md](research/manga-compat.md): 9/10, comix needs a WebView). The Cloudflare/WebView shim is deferred (phase-2 §5).

### Phase 3 — Frontend MVP
Details: [phase-3-frontend-mvp.md](phases/phase-3-frontend-mvp.md)

- Screens: Browse (sources, popular/latest/search + filters), Details, Library, Extensions, Settings.
- A generic filter renderer driven by the backend (text, select, checkbox, tri-state, sort, group).
- Extension preference renderer (list, edit text, switch, multi-select).
- Exit: browse anime + manga end-to-end in the UI.

Milestones:
- [ ] Tauri sidecar lifecycle (spawn, handshake, restart)
- [ ] Typed API client + WebSocket
- [ ] Repos, Extensions (with trust prompt), Browse, Source, Details, Library, Settings screens
- [ ] Generic filter and preference renderers
- [ ] lint / typecheck / tests pass

### Phase 4 — Playback
Details: [phase-4-playback.md](phases/phase-4-playback.md)

- mpv sidecar per playback session: launch with headers, subs and audio tracks; control over JSON IPC.
- Quality/server picker from `getVideoList`.
- Progress sync (time-pos → DB every N sec), mark watched at a threshold, next-episode.
- Later: embed mpv in the app window (`--wid` child HWND) instead of a separate window.
- Exit: watch an episode and resume where you stopped.

Milestones:
- [ ] mpv launcher with headers / subs / audio
- [ ] Named-pipe IPC + progress saving
- [ ] Video picker, resume prompt, watched threshold
- [ ] Next-episode flow
- [ ] Expired-URL retry / fallback

### Phase 5 — Manga reader
Details: [phase-5-manga-reader.md](phases/phase-5-manga-reader.md)

- Modes: paged (LTR/RTL), vertical webtoon, double page.
- Images go through the backend proxy (source headers/cookies), with prefetch and a disk cache.
- Exit: smooth reading of long webtoon chapters.

Milestones:
- [ ] Image proxy + disk cache + prefetch
- [ ] Paged LTR/RTL, webtoon, double-page modes
- [ ] Reading progress + resume
- [ ] 100+ page webtoon chapter scrolls smoothly

### Phase 6 — Library features
Details: [phase-6-library-features.md](phases/phase-6-library-features.md)

- Categories, update checker (scheduled), history, unread/unwatched badges.
- Download queue: manga as CBZ; anime via mpv/yt-dlp/ffmpeg using the source headers, with HLS support.
- Backup/restore. Stretch: import Aniyomi/Mihon backup files.

Milestones:
- [ ] Categories
- [ ] Scheduled update checker + Updates view + badges
- [ ] History
- [ ] Download queue (manga CBZ, anime incl. HLS)
- [ ] Backup / restore round-trip

### Phase 7 — Packaging & release
Details: [phase-7-packaging-release.md](phases/phase-7-packaging-release.md)

- `jlink` minimal JRE; bundle `mpv.exe`; Tauri NSIS/MSI installer.
- Tauri auto-updater for the app; the backend updates with it.
- Crash/log collection (local log files, an "export logs" button).

Milestones:
- [ ] jlink JRE + bundled mpv (and ffmpeg/yt-dlp)
- [ ] NSIS/MSI installers work on a clean machine without Java
- [ ] Auto-update N → N+1 keeps user data
- [ ] Logs + "export logs"
- [ ] CI release workflow

### Phase 8 — Later
Details: [phase-8-later.md](phases/phase-8-later.md)

- Trackers (AniList, MyAnimeList, Kitsu).
- Discord Rich Presence.
- Torrent sources (some Aniyomi extensions return magnet links), e.g. via a torrent library sidecar.

Milestones:
- [ ] Trackers (AniList, MyAnimeList, Kitsu)
- [ ] Discord Rich Presence
- [ ] Torrent sources

---

## 5. Backend API sketch

```
GET    /api/repos                         list repos
POST   /api/repos                         { url, type: "anime"|"manga" }
GET    /api/extensions?type=anime         available + installed
POST   /api/extensions/{pkg}/install
POST   /api/extensions/{pkg}/trust
GET    /api/sources?type=anime
GET    /api/sources/{id}/popular?page=
GET    /api/sources/{id}/search?q=&page=&filters=
GET    /api/sources/{id}/filters
GET    /api/sources/{id}/preferences      → JSON schema
PUT    /api/sources/{id}/preferences
GET    /api/anime/{id}                    details (+ refresh)
GET    /api/anime/{id}/episodes
GET    /api/episodes/{id}/videos          → [{ url, quality, headers, subtitles[], audio[] }]
POST   /api/player/play                   { episodeId, videoIndex } → backend launches mpv
GET    /api/manga/{id}/chapters
GET    /api/chapters/{id}/pages
GET    /api/image?src=&sourceId=          image proxy
WS     /ws                                downloads, updates, playback progress events
```

**Decided in Phase 0: keep Suwayomi's GraphQL API and add anime types to it.** The endpoints above map to GraphQL queries and mutations. REST stays only for binaries (`/api/image`, page bytes, later a video proxy). The mapping of the repo, extension, trust, source and event endpoints is in [phase-1 step 17](phases/phase-1-backend-foundation.md#api); the anime source, filter, preference, browse, details, episode and video endpoints, and the anime thumbnail, are mapped in [phase-2 step 16](phases/phase-2-anime-sources.md#api).

---

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Android API surface used by extensions isn't stubbed | Extensions crash | Log missing classes/methods; build a compatibility test suite over the repo |
| `extensions-lib` version changes (anime lib differs from manga lib) | Breakage across all extensions | Pin versions; support multiple lib versions side by side if needed |
| Cloudflare / WebView-dependent extensions | Some sources unusable | Headless browser shim; FlareSolverr as fallback. Measured in Phase 2: 1 of the top 20 is blocked (AnimePahe), so the solver is deferred. A real WebView is a separate need: 1 of the 10-extension manga sample (comix) renders its site in one, and the sidecar runs with KCEF off |
| Video URLs require headers/cookies | 403s in the player | Always pass `Video.headers` to mpv; proxy fallback |
| Extensions are arbitrary JVM code | Security | Trust prompt by signing cert; only user-added repos; consider a separate JVM process per extension type |
| R8-optimised APKs that dex2jar mistranslates (e.g. Keiyoushi's removed constructors and merged classes) | Extensions fail to load (`VerifyError`) | A bytecode repair pass after dex2jar (`Dex2jarRepair`: constructors restored, frames recomputed with the APK's class hierarchy); the Phase 2 compat scan covers a 10-extension manga sample. It found two more patterns there (a super call that skips a removed constructor; a `new T` dex2jar leaves unfolded), fixed in Stage 6: all 30 APKs verify. Rerun `tools/compat-test/scan.ps1` when the lists are refreshed |
| Protobuf index schema undocumented | Can't list manga extensions | Reverse-engineer from Mihon's client source; JSON fallback if one exists |
| License mixing | Suwayomi is MPL-2.0, Aniyomi code is Apache-2.0 | Madoyomi is MPL-2.0; keep upstream headers; add a NOTICE/THIRD_PARTY file |

---

## 7. Open decisions

- [x] Fork Suwayomi-Server, or write a fresh backend reusing its extension-host code? **Fork** (phase-0 §9; confirmed by the S1 run and the S3 harness running on Suwayomi's runtime unchanged).
- [x] REST, or extend Suwayomi's GraphQL? **Extend GraphQL**, keep REST for binary endpoints (phase-0 §9).
- [ ] mpv in a separate window (MVP) vs. embedded (later): confirm UX priority
- [x] One JVM process for both extension types, or isolated processes? **One JVM** (shared network/DI/stub layer). Crash isolation is deferred: an in-process extension can't be contained without a separate JVM anyway, so the trust gate is the containment. Revisit if Phase 2's compat harness shows hard crashes (phase-1 §8, §9). **Verdict (Phase 2 Stage 6): one JVM stays.** Two harness runs of the top 20 in one JVM had no abort, bounded heap and threads. The class loaders kept after uninstall went from 15 to 1 (a connection reanime keeps open; phase-1 §8, phase-2 §10).
- [x] Name: Madoyomi · License: MPL-2.0
- [ ] Windows-only, or keep macOS/Linux possible (the architecture already allows it)?

---

## 8. First tasks (start here)

These are the Phase 0 spikes; see [phase-0-spikes.md](phases/phase-0-spikes.md) for the detailed steps. **All done as of 2026-11-03.** [Phase 1](phases/phase-1-backend-foundation.md) and [Phase 2](phases/phase-2-anime-sources.md) are done too (2026-11-06); next up is [Phase 3](phases/phase-3-frontend-mvp.md).

1. Clone Suwayomi-Server, run it, add the Keiyoushi repo, and confirm manga works.
2. Download one yuzono APK, dex2jar it, and inspect which `extensions-lib` classes it references.
3. Write a tiny Kotlin harness that loads that jar and calls `getPopularAnime(1)`.
4. Play one returned video in mpv with its headers.
5. ~~Scaffold Tauri 2 + Vue 3 in `madoyomi/app`~~ (done in `547f422`), then spawn a dummy JVM sidecar.
