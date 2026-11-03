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
| Extension loading | dex2jar + custom classloader + Android stubs | Already solved for manga in Suwayomi |
| Anime API | Aniyomi `extensions-lib` (`eu.kanade.tachiyomi.animesource.*`) | Match the lib version yuzono builds against (APK names show `v14.x`) |
| Manga index parsing | `kotlinx.serialization.protobuf` | For `index.pb`; check if Suwayomi already supports it |
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
/server         Kotlin backend (Suwayomi fork or module)
  /core         DB, library, downloads, settings
  /ext-host     APK loading, classloader, Android stubs
  /ext-manga    Mihon source adapter (from Suwayomi)
  /ext-anime    Aniyomi animesource adapter (new)
  /api          REST/GraphQL + WebSocket
/player         mpv config, scripts (e.g. uosc), IPC client contract
/tools          jlink script, packaging, test-extension fetcher
```

---

## 4. Phases

### Phase 0 — Spikes (de-risk first, ~1–2 weeks)
Goal: prove each hard part works in isolation before committing.

1. **Manga extension on JVM:** run Suwayomi-Server, add the Keiyoushi repo, install 2–3 extensions, browse + read.
2. **Protobuf index:** parse `index.pb` and work out its schema (fields → name, pkg, apk, version, sources).
3. **Anime extension on JVM:** take one yuzono APK, dex2jar it, load it against Aniyomi `extensions-lib` stubs, call `getPopularAnime` → `getEpisodeList` → `getVideoList`.
4. **Playback:** feed a returned `Video` (URL + headers + subtitle tracks) into mpv and confirm it plays.
5. **Tauri sidecar:** Tauri spawns the JVM jar, gets its port, and Vue calls it.

Exit: all five work. Decide **fork Suwayomi vs. depend on it as a library** (fork is likely more practical).

### Phase 1 — Backend foundation
- Fork Suwayomi-Server and rebrand to Madoyomi (keep MPL-2.0 headers and attribution on Suwayomi-derived files).
- Add an `anime` domain alongside `manga`: entities, DB tables, API namespaces.
- Repo manager handles both index formats (JSON for anime, protobuf for manga).
- Extension install/update/uninstall, with **trust prompt** by signing-cert hash (Mihon/Aniyomi model).
- Exit: install and list extensions for both types from the API.

### Phase 2 — Anime source support
- Implement the Aniyomi `animesource` interfaces and models: `AnimeCatalogueSource`, `AnimeHttpSource`, `SAnime`, `SEpisode`, `Video` (with headers, subtitle tracks and audio tracks), filters and preferences.
- Add Android stubs anime extensions commonly need (`SharedPreferences`, `Context`, `Uri`, `Base64`, `Log`, etc.).
- Expose source preferences (extension settings screens) as JSON schema for the UI.
- Exit: the top ~20 yuzono extensions browse, search, list episodes and return videos.

### Phase 3 — Frontend MVP
- Screens: Browse (sources, popular/latest/search + filters), Details, Library, Extensions, Settings.
- A generic filter renderer driven by the backend (text, select, checkbox, tri-state, sort, group).
- Extension preference renderer (list, edit text, switch, multi-select).
- Exit: browse anime + manga end-to-end in the UI.

### Phase 4 — Playback
- mpv sidecar per playback session: launch with headers, subs and audio tracks; control over JSON IPC.
- Quality/server picker from `getVideoList`.
- Progress sync (time-pos → DB every N sec), mark watched at a threshold, next-episode.
- Later: embed mpv in the app window (`--wid` child HWND) instead of a separate window.
- Exit: watch an episode and resume where you stopped.

### Phase 5 — Manga reader
- Modes: paged (LTR/RTL), vertical webtoon, double page.
- Images go through the backend proxy (source headers/cookies), with prefetch and a disk cache.
- Exit: smooth reading of long webtoon chapters.

### Phase 6 — Library features
- Categories, update checker (scheduled), history, unread/unwatched badges.
- Download queue: manga as CBZ; anime via mpv/yt-dlp/ffmpeg using the source headers, with HLS support.
- Backup/restore. Stretch: import Aniyomi/Mihon backup files.

### Phase 7 — Packaging & release
- `jlink` minimal JRE; bundle `mpv.exe`; Tauri NSIS/MSI installer.
- Tauri auto-updater for the app; the backend updates with it.
- Crash/log collection (local log files, an "export logs" button).

### Phase 8 — Later
- Trackers (AniList, MyAnimeList, Kitsu).
- Discord Rich Presence.
- Torrent sources (some Aniyomi extensions return magnet links), e.g. via a torrent library sidecar.

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

Alternative: keep Suwayomi's GraphQL API and add anime types to it. Decide after Phase 0.

---

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Android API surface used by extensions isn't stubbed | Extensions crash | Log missing classes/methods; build a compatibility test suite over the repo |
| `extensions-lib` version changes (anime lib differs from manga lib) | Breakage across all extensions | Pin versions; support multiple lib versions side by side if needed |
| Cloudflare / WebView-dependent extensions | Some sources unusable | Headless browser shim; FlareSolverr as fallback |
| Video URLs require headers/cookies | 403s in the player | Always pass `Video.headers` to mpv; proxy fallback |
| Extensions are arbitrary JVM code | Security | Trust prompt by signing cert; only user-added repos; consider a separate JVM process per extension type |
| Protobuf index schema undocumented | Can't list manga extensions | Reverse-engineer from Mihon's client source; JSON fallback if one exists |
| License mixing | Suwayomi is MPL-2.0, Aniyomi code is Apache-2.0 | Madoyomi is MPL-2.0; keep upstream headers; add a NOTICE/THIRD_PARTY file |

---

## 7. Open decisions

- [ ] Fork Suwayomi-Server, or write a fresh backend reusing its extension-host code?
- [ ] REST, or extend Suwayomi's GraphQL?
- [ ] mpv in a separate window (MVP) vs. embedded (later): confirm UX priority
- [ ] One JVM process for both extension types, or isolated processes?
- [x] Name: Madoyomi · License: MPL-2.0
- [ ] Windows-only, or keep macOS/Linux possible (the architecture already allows it)?

---

## 8. First tasks (start here)

1. Clone Suwayomi-Server, run it, add the Keiyoushi repo, and confirm manga works.
2. Download one yuzono APK, dex2jar it, and inspect which `extensions-lib` classes it references.
3. Write a tiny Kotlin harness that loads that jar and calls `getPopularAnime(1)`.
4. Play one returned video in mpv with its headers.
5. Scaffold Tauri 2 + Vue 3 in `madoyomi/app` and spawn a dummy JVM sidecar.
