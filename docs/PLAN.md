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
│  Sidecar: mpv.exe  ← JSON IPC (named pipe), started and driven by the backend      │
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
- [x] Tauri sidecar lifecycle (spawn, handshake, restart) (Stage 1: the real jar with a token and `--data-dir`, `/health` checks, restart with backoff, splash and failure screens; phase-3 §10)
- [x] Typed API client + WebSocket (Stage 2: `api/client.ts`, hand-written `types.ts` validated against the live schema, our own `graphql-transport-ws` client that follows backend restarts, vue-query hooks with infinite browse; phase-3 §9, §10)
- [x] Repos, Extensions (with trust prompt), Browse, Source, Details, Library, Settings screens (Stage 3: Repos and Extensions with the trust prompt; Stage 4: Browse and Source; Stage 5: Details for both kinds and the Library; Stage 6: Settings and each source's settings; all verified live, phase-3 §10)
- [x] Generic filter and preference renderers (Stage 4: `FilterRenderer`, verified on shared fixtures and live for both kinds; Stage 6: `PreferenceRenderer`, every preference type by component tests, and live a list, a switch, a multi-select and an edit text taking effect for both kinds)
- [x] lint / typecheck / tests pass (Stage 7: `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` clean, the live spec against a seeded server, `cargo test` and `cargo clippy`; phase-3 §10)

**Phase 3 done (2026-11-07).** Every exit criterion passes, checked live in `pnpm tauri dev` from a fresh, empty data dir, all through the UI (phase-3 §10, "Stage 7"):
- both repos added (the type detected), KickAssAnime, MangaPill and MangaDex installed with their steps shown, and the trust prompt used;
- a search, and a filtered search of each kind that changed the results;
- details with the episodes or chapters, and one of each kind added to the Library;
- a preference of each kind taking effect;
- a backend kill: the overlay, then everything working on the new port without a reload;
- the failure screen's Retry and Open logs.

The app now has every MVP screen:
- Repos and Extensions, with the trust prompt;
- Browse and the source pages, with the generic filter renderer and an endless cover grid;
- Details with virtualized episode and chapter lists, and the Library;
- Settings, with each source's preferences through the generic preference renderer.

The shell runs the real backend with a per-session token and restarts it after a crash. The UI talks to it through typed GraphQL and live WebSocket events that follow a restart (phase-3 §9).

The exit run's fix round:
- one backend change: the cover and chapter-page caches moved into the data dir, because they were shared across data dirs and keyed by database id, which showed wrong covers;
- Retry now finds a jar that was missing at app start;
- the source page searches what its box shows;
- a details page keeps the sidebar section it was opened from.

### Phase 4 — Playback
Details: [phase-4-playback.md](phases/phase-4-playback.md)

- mpv sidecar per playback session: launch with headers, subs and audio tracks; control over JSON IPC.
- Quality/server picker from `getVideoList`.
- Progress sync (time-pos → DB every N sec), mark watched at a threshold, next-episode.
- Later: embed mpv in the app window (`--wid` child HWND) instead of a separate window.
- Exit: watch an episode and resume where you stopped.

Milestones:
- [x] mpv launcher with headers / subs / audio (Stage 1: `MpvCommand` with S4's header forms and the source's values in a per-session include file, `PlaybackSessions` owning one mpv at a time, `playEpisode`/`stopPlayback`; AnimeGG's MP4 and KickAssAnime's HLS with 8 subtitles and 2 audio tracks played live; phase-4 §10)
- [x] Named-pipe IPC + progress saving (Stage 2: `MpvIpcClient` with overlapped I/O on the pipe, progress saved every 5 s and on pause, seek and quit into `EPISODE` in milliseconds, the episode marked watched once at 85 %, the `playbackEvents` subscription; tested on a real named pipe with a fake mpv, and live with AnimeGG and KickAssAnime; phase-4 §10)
- [x] Video picker, resume prompt, watched threshold (Stage 3: `VideoPicker` with the last choice per source, the "Resume from mm:ss / Start over" footer, live rows and the Watched badge from `playbackEvents`, bulk marks through `updateEpisodes`; the threshold itself is Stage 2's; live in `pnpm tauri dev`, where a resume after an app restart was 0.00 s off; phase-4 §10)
- [ ] Next-episode flow
- [ ] Expired-URL retry / fallback

**Phase 4 in progress: Stage 3 of 6 done (2026-11-11).** The stage plan and the two decisions are in phase-4 §8–§9: mpv plays **in its own window** for the MVP (embedding is a stretch goal), and **the backend owns the mpv process**. Stage 1 added the launcher and the process handling:
- `madoyomi.core.player` finds mpv (the override, `MADOYOMI_MPV`, PATH), builds its command line and runs one session at a time;
- the source's headers, tracks and options reach mpv through a per-session include file, not the command line;
- mpv dies with the app (Job Object) and with the backend (shutdown hook), and a restarted backend ends the mpv a crashed one left;
- the API has `playEpisode`, `stopPlayback` and `playbackSession`, and each episode row has a Play button.

Stage 2 added the IPC pipe and progress:
- the backend talks to mpv over its named pipe (`MpvIpcClient`, overlapped I/O) and observes the position, duration, pause, end of file and tracks;
- progress goes into the episode's existing `last_second_seen`/`total_seconds`, in milliseconds as the Aniyomi app stores them: every 5 s, and at once on pause, seek and quit. The episode is marked watched once at 85 %;
- the subscription `playbackEvents` reports STARTED, PROGRESS, WATCHED, ENDED (with the next episode), ERROR and CLOSED, and the app refetches the episode list on WATCHED and CLOSED;
- external subtitle and audio tracks are added over IPC with their labels as titles, and a session is stopped with `quit` before it would be killed.

Stage 3 added the picker, resuming and the watched state in the UI:
- Play opens the video picker (`VideoPicker`): each hoster with its videos (Load for a lazy one, Retry for a failed one), quality and track counts, "Source's pick", and the last choice per source on this device preselected as "Last used";
- its footer offers "Resume from mm:ss" and "Start over" between 1 % and 85 %; a watched episode starts over;
- the playing row shows "Playing"/"Paused", the time left and a bar from the live events, without refetching, and the Watched badge appears the moment the episode passes 85 %;
- episodes can be selected (Shift+click for a range) and marked watched or unwatched, or "Mark previous as watched", through the new `updateEpisode`/`updateEpisodes`. Unwatched clears the position.

Both §6 criteria it covers passed live in `pnpm tauri dev` (resume within ±5 s after an app restart, the badge without a refresh).

Next: Stage 4, the next episode and errors: the next-episode toast with a countdown and auto-play, one refetch of the videos on a 403/404 or an early exit, then the next video offered, and the proxy fallback decided by measurement.

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
| Video URLs require headers/cookies | 403s in the player | Always pass `Video.headers` to mpv; proxy fallback. Since Phase 4 Stage 1 every header reaches mpv verbatim through a per-session include file (S4's forms, one `http-header-fields-append` each), checked live with AnimeGG (Referer required) and KickAssAnime (7 headers), and in Stage 2 for the subtitle and audio tracks added over IPC too; the proxy fallback is decided by measurement in Stage 4 |
| Extensions are arbitrary JVM code | Security | Trust prompt by signing cert; only user-added repos; consider a separate JVM process per extension type. Since Phase 3 Stage 3 the UI's trust prompt shows the certificate hash and warns that a trusted extension runs with the user's rights, and removing a repo asks first and says which extensions stop loading |
| R8-optimised APKs that dex2jar mistranslates (e.g. Keiyoushi's removed constructors and merged classes) | Extensions fail to load (`VerifyError`) | A bytecode repair pass after dex2jar (`Dex2jarRepair`: constructors restored, frames recomputed with the APK's class hierarchy); the Phase 2 compat scan covers a 10-extension manga sample. It found two more patterns there (a super call that skips a removed constructor; a `new T` dex2jar leaves unfolded), fixed in Stage 6: all 30 APKs verify. Rerun `tools/compat-test/scan.ps1` when the lists are refreshed |
| Protobuf index schema undocumented | Can't list manga extensions | Reverse-engineer from Mihon's client source; JSON fallback if one exists |
| License mixing | Suwayomi is MPL-2.0, Aniyomi code is Apache-2.0 | Madoyomi is MPL-2.0; keep upstream headers; add a NOTICE/THIRD_PARTY file |

---

## 7. Open decisions

- [x] Fork Suwayomi-Server, or write a fresh backend reusing its extension-host code? **Fork** (phase-0 §9; confirmed by the S1 run and the S3 harness running on Suwayomi's runtime unchanged).
- [x] REST, or extend Suwayomi's GraphQL? **Extend GraphQL**, keep REST for binary endpoints (phase-0 §9).
- [x] mpv in a separate window (MVP) vs. embedded (later)? **A separate window for the MVP**; embedding with `--wid` stays a stretch goal after the Phase 4 exit run. **The backend owns the mpv process** (phase-4 §9, Stage 1).
- [x] One JVM process for both extension types, or isolated processes? **One JVM** (shared network/DI/stub layer). Crash isolation is deferred: an in-process extension can't be contained without a separate JVM anyway, so the trust gate is the containment. Revisit if Phase 2's compat harness shows hard crashes (phase-1 §8, §9). **Verdict (Phase 2 Stage 6): one JVM stays.** Two harness runs of the top 20 in one JVM had no abort, bounded heap and threads. The class loaders kept after uninstall went from 15 to 1 (a connection reanime keeps open; phase-1 §8, phase-2 §10).
- [x] UI components and theme? **Headless primitives (Reka UI) with our own styles; dark and light themes that follow Windows; Fluent-like tokens** (phase-3 §9, Stage 1). The window keeps the **native Windows title bar** (Snap Layouts, caption buttons; phase-3 §9, Stage 2).
- [x] Name: Madoyomi · License: MPL-2.0
- [ ] Windows-only, or keep macOS/Linux possible (the architecture already allows it)?

---

## 8. First tasks (start here)

These are the Phase 0 spikes; see [phase-0-spikes.md](phases/phase-0-spikes.md) for the detailed steps. **All done as of 2026-11-03.** [Phase 1](phases/phase-1-backend-foundation.md) and [Phase 2](phases/phase-2-anime-sources.md) are done too (2026-11-06), and so is [Phase 3](phases/phase-3-frontend-mvp.md) (2026-11-07, seven stages: the shell and sidecar, the API layer, Repos and Extensions, Browse and Source, Details and Library, Settings, and the exit run).

[Phase 4](phases/phase-4-playback.md) (playback in mpv) is in progress: Stage 3 of 6 is done (2026-11-11). Stage 1 settled mpv in its own window, owned by the backend (§7), and added the launcher and Play. Stage 2 added the IPC client, progress saving, the watched mark and the playback events. Stage 3 added the video picker, the resume prompt, live progress and the Watched badge in the episode list, and bulk marks (`updateEpisodes`). Stage 4 adds the next-episode flow and the retry on expired URLs.

1. Clone Suwayomi-Server, run it, add the Keiyoushi repo, and confirm manga works.
2. Download one yuzono APK, dex2jar it, and inspect which `extensions-lib` classes it references.
3. Write a tiny Kotlin harness that loads that jar and calls `getPopularAnime(1)`.
4. Play one returned video in mpv with its headers.
5. ~~Scaffold Tauri 2 + Vue 3 in `madoyomi/app`~~ (done in `547f422`), then spawn a dummy JVM sidecar.
