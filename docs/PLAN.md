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
- [x] Next-episode flow (Stage 4: the toast with a 10 s countdown after an episode ends, auto-play with the remembered choice and no picker, from the resume point, on by default in Settings → Playback; Cancel, another Play and leaving the page stop it; live in `pnpm tauri dev`, where episode 4 started from episode 3's end with the remembered video; phase-4 §10)
- [x] Expired-URL retry / fallback (Stage 4: a refused or expired URL (the HTTP status from mpv's log) or a start that never played fetches the videos again once and plays the same video from where it failed; if that fails, the picker reopens with the next video preselected; live with an expiring URL from the header proxy, through the API and in `pnpm tauri dev`; phase-4 §10)

**Phase 4 done (2026-11-12).** Every exit criterion passes, checked live in `pnpm tauri dev` from a fresh, empty data dir, through the UI, and again on a second fresh data dir after the fix round (phase-4 §10, "Stage 6"):
- the yuzono repo added (type detected), AnimeGG, KickAssAnime and AnimeOnsen installed with their steps, the trust prompt used, and the player settings from their defaults;
- an episode watched, mpv's window closed halfway, the app closed and reopened: "Resume from 12:32" (then 11:56 in the second run), and mpv 0.00 s off the stored position both times;
- the Watched badge the moment the episode passed 85 %, and, with the threshold changed to 70 % while an episode played, at 72 % in the same session;
- the end of the episode: the toast's countdown played the next episode with the remembered video, without the picker; Cancel played nothing.

The step 20 format matrix passed live through the API: MP4 (AnimeGG), HLS (KickAssAnime), an MKV with ASS subtitles and two audio tracks of its own (a generated file in place of a source's URL, since none of the top 20 serves MKV), external subtitles (KickAssAnime's WebVTT, AnimeOnsen's ASS), external audio, and expiring URLs at once and mid-play for MP4 and HLS. Each row played, selected the expected tracks, saved its progress and got its headers verbatim. The fix round: an HLS stream that opens but never plays a frame is now fetched again once like any early failure (found through the header proxy), and a flaky thread-count test counts by name. The embedded `--wid` prototype stays a stretch goal, and the header proxy fallback stays deferred.

The stage plan and the two decisions are in phase-4 §8–§9: mpv plays **in its own window** for the MVP (embedding is a stretch goal), and **the backend owns the mpv process**. Stage 1 added the launcher and the process handling:
- `madoyomi.core.player` finds mpv (the player settings' path since Stage 5, `MADOYOMI_MPV`, PATH), builds its command line and runs one session at a time;
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

Stage 4 added the next episode and the error handling:
- when an episode ends (or is quit past 85 %), a toast offers the next one with a 10 s countdown, then plays it with the remembered choice for the source, without the picker, from its resume point. Auto-play is on by default (Settings → Playback, now Player, on this device), and Cancel, another Play or leaving the page stops it;
- when mpv can't open the video (it never played, or its log has an HTTP 401/403/404/410, or a URL that expired while playing ended the file early), the backend fetches the videos again once and plays the same video from where it failed (the new RETRYING event). If that fails, ERROR names the next video in the source's order, and the app reopens the picker with it preselected;
- the proxy fallback is deferred: every header of AnimeGG and KickAssAnime reached mpv's requests verbatim (§6).

The §6 criterion "the next episode starts from the end-of-episode prompt" passed live in `pnpm tauri dev`.

Stage 5 added the player settings (Settings → **Player**):
- they are the backend's, in `<data dir>/player-settings.json`, through `playerSettings`/`setPlayerSettings` (plus `playerLanguages` and `mpvCheck`), since the backend applies them; each change is used from the next play. Auto-play stays a device preference, in the same section;
- **Mark as watched at** 50–95 % (default 85 %): the watched mark, read at every position, and the resume prompt;
- **preferred quality and server**: the backend sorts each episode's hosters and videos by them after the source's own sort (the server first, then the closest quality) and marks the matches. The picker marks that pick **Preferred**; what it preselects, and what auto-play plays, is the last choice for the source, else the preference's, else the source's (the user's decision, phase-4 §9);
- **subtitle and audio language** (or subtitles off): once the tracks load, the backend sets mpv's `sid`/`aid` to the first track in that language, the file's own or the source's, else Stage 2's rule;
- **mpv**: a path that comes before `MADOYOMI_MPV` and PATH, with Check (`mpv --version`); a wrong one is `PLAYER_NOT_FOUND` naming the setting.

All of it was checked live through the API and in `pnpm tauri dev` (phase-4 §10).

Stage 6 was the exit run (above).

### Phase 5 — Manga reader
Details: [phase-5-manga-reader.md](phases/phase-5-manga-reader.md)

- Modes: paged (LTR/RTL), vertical webtoon, double page.
- Images go through the backend proxy (source headers/cookies), with prefetch and a disk cache.
- Exit: smooth reading of long webtoon chapters.

Milestones:
- [x] Image proxy + disk cache + prefetch (Stage 1: upstream's page route is the proxy, with the source's headers and cookie jar; Madoyomi's `PageImages`/`PageCache` add one fetch per page at a time, one retry with an expired image URL got anew, `SOURCE_FAILED` with the HTTP status, the 1 GB cap with LRU eviction, and `prefetchChapterPages`; tested on a loopback image host, and live through the API on MangaPill, a 103-page Webtoons chapter and MangaDex, and Settings → Reader in `pnpm tauri dev`; phase-5 §10)
- [x] Paged LTR/RTL, webtoon, double-page modes (Stage 2: paged left to right and right to left, verified live on MangaPill; Stage 3: webtoon, verified live on Webtoons chapters of 103 and 95 pages and on 800×30000 pages; Stage 4: double page, the first page alone, wide pages alone, "Shift" by one, in both directions, verified live on a generated chapter with spreads at an odd and an even index (no real source's double-page chapter was read), with zoom and pan and the seamless next chapter in every mode; phase-5 §10. Stage 5: the mode and the direction remembered per manga (its meta) and the default mode by genre or source, verified live, after an app restart too. Stage 6, the exit run: every mode on a real source from two fresh data dirs, a real wide page alone in Double (Kingdom's credits page), each manga's mode and direction after a reopen and an app restart)
- [x] Reading progress + resume (Stage 2: the page saved through upstream's `updateChapter`, debounced, the chapter marked read on its last page, and resume at `lastPageRead`, after an app restart too; with the backend killed mid-chapter, the failed read mark was written once it was back; Stage 6: resume in every mode from two fresh data dirs, and a reopened double-page spread no longer moves the place forward; phase-5 §5, §10)
- [x] 100+ page webtoon chapter scrolls smoothly (Stage 6, the exit run, **on the dev desktop** (NVIDIA GPU, 145 Hz): no mid-range laptop was at hand, and the user closed the criterion there with this note. The 103-page Webtoons chapter from a fresh data dir, cold, top to bottom at a normal speed with real wheel input: 0 frames over 50 ms (the longest 16 ms) and 0 blank frames after the fix round, 674 ms to the first page; 5 chapters in one go (472 pages) with no frame over 50 ms and the memory flat. Stage 3's first numbers were on the same machine; phase-5 §6, §10)

**Phase 5 done (2026-11-14).** Every exit criterion passes, checked live in `pnpm tauri dev` from a fresh, empty data dir, through the UI, and the affected checks again on a second fresh data dir after the fix round (phase-5 §10, "Stage 6"):
- the Keiyoushi repo added (type detected), MangaPill, MangaDex and Webtoons.com installed, and the reader settings from their defaults;
- Webtoons "Osora" (S2) Chapter 126 (103 pages), cold, top to bottom at a normal speed: 0 frames over 50 ms (the longest 16 ms) and 0 blank frames after the fix round, 674 ms to the first page; 5 chapters in one go (472 pages) with no frame over 50 ms; the WebView2 processes' memory flat over them (512 MB working set before, 479 MB after). Measured on the dev desktop, by the user's decision (no mid-range laptop was at hand);
- every mode on a real source: Single both ways, Double with Shift and a wide page alone, Webtoon by its source and by its genre; each manga's own mode and direction kept after a reopen and an app restart; a global default reaching a manga with none;
- resume in every mode, after a reopen and after an app restart.

The fix round:
- a chapter crossing showed 2–7 blank frames while the new chapter's first page decoded: the pages a neighbour opens on are now held decoded ahead and taken over;
- a new page fit after a pan kept the pan: it goes back to the start view;
- a reopened double-page spread saved its other page, so the place moved forward a page on each reopen: the page it opened on is saved until the reader moves;
- the dev overlay's first-page time, and the flaky `MpvIpcClientTest` (its test-only race).

Tests: 11 new in the app (578 + 11), the server's 794 green with the test-only fix; no change to the server's code, the shell or the operations.

The stage plan and both of the phase's decisions are in phase-5 §8–§9:
- **the default reader mode:** a manga's own mode, else webtoon when its genres say Long Strip, Webtoon, Manhwa or Manhua, else (Stage 5, the user's) webtoon when its source's name says Webtoon, else the global default, paged right-to-left (Mihon's);
- **the page cache:** 1 GB by default (256 MB to 10 GB in Settings → Reader), in `<data dir>/cache/manga`, the least recently used pages going first.

Stage 5 added the reader settings (phase-5 §9 "Stage 5"):
- per manga, in its meta (upstream's `setMangaMeta`/`deleteMangaMeta`, so they go into backups): the mode and the direction (`madoyomi.reader.mode`, `madoyomi.reader.direction`). The toolbar's mode and direction switches save this manga's own, as in Mihon;
- global, on this device (`stores/reader.ts`, `madoyomi.reader` in localStorage, read field by field): the default mode and direction, the page fit of the paged modes (fit screen, width, height, original size), the strip's width (600–1200 px or the window's), the page gap, the background (dark, black, gray, white) and the preload count (2–12 pages ahead, which also moves the backend's prefetch block). Shift stays for the session;
- the mode resolved in the pure `utils/readerSettings.ts` (the manga's own, its genres, its source's name, the global default); the reader waits for the meta before it shows a page;
- the toolbar's Settings opens a sheet ("This manga" with "Use default" and a reset; "All manga"), and Settings → Reader has the same global fields with a reset;
- a page bigger than the window (any fit but fit screen) starts at its top and scrolls with the wheel, the keys and a drag before the page turns;
- no server change; 3 new documents (53), with a live-spec round trip of the meta.

Stage 4 added the double-page mode, zoom and pan, and the seamless next chapter (phase-5 §9 "Stage 4"):
- the double page in `PagedReader` (no separate component), over the pure `utils/doublePage.ts`. Page 1 is alone (the user's choice), and so is a page wider than tall, with the pairs starting again after it. "Shift" pairs from page 1. Right to left, the first page is on the right. The page shown stays on screen when a size changes the pairs, and progress counts the spread's last page;
- zoom and pan in the single and double modes (`utils/zoom.ts`): Ctrl+wheel about the cursor, Ctrl+=/−/0, a double-click in the middle third (the user's choice: the side thirds still turn at once), 1× to 4× clamped to the page, panning by a drag, the wheel or the arrows, and back to fit on a turn or Esc. None in the webtoon strip;
- the seamless next chapter: the panels are the transition ("Next chapter: …"), and the next (previous) chapter's page list is fetched ahead, so one more step opens it at once, in place. In the strip, one strip per chapter (the user's choice), a new wheel gesture past its end goes on;
- the toolbar's Single / Double / Webtoon control and Shift, and buttons that leave the keys to the reader after a mouse click;
- no server change.

Stage 3 added the webtoon mode (phase-5 §9 "Stage 3"):
- `WebtoonReader`: the chapter as one strip, at most 800 px wide, scrolled natively, with only the pages near the viewport in the DOM. Their heights are computed from each image's ratio (`utils/webtoonNav.ts`, pure and unit-tested), and the strip keeps what you look at still when a page's size arrives or the window changes;
- pages loaded by pixels around the viewport (a screen behind, two ahead), still 4 requests at a time, each decoded before it shows; very tall images shown whole;
- the toolbar's Paged/Webtoon switch and page gap, for the session; the keys scroll by a line or a screen, and past the strip's ends open the next or previous chapter; reaching the end marks the chapter read;
- the first step-15 numbers: no frame over 50 ms and no blank frame while scrolling 103 pages, 552–642 ms to the first page, the WebView2 processes' memory where it started after the chapter;
- no server change.

Stage 2 added the reader (`/manga/:id/read/:chapterId?page=N`, without the app shell; phase-5 §9 "Stage 2"):
- the paged mode, right to left by default and left to right from the toolbar for the session: click zones, keys and the wheel, the page slider, previous and next chapter (the next one in the source's order, another group's copy skipped), panels at both ends of a chapter, fullscreen;
- pages fetched as blobs with the token in the header, at most 4 at a time around the reader, the backend prefetching the next block and the next chapter; a failed page shows the source's HTTP status and "Click to retry", and pages that failed while the backend was away come back by themselves;
- progress: the page saved (debounced, and on leaving), the chapter marked read on its last page, resume where it stopped; a failed save is tried again;
- the chapter list: rows open the reader (Read / Continue, "Page 13 of 92"), the read filter, bulk read marks; the details page's "Start reading" / "Continue: …";
- no server change: upstream's `updateChapter(s)` in the app (50 documents in the live spec).

Stage 1 added the image pipeline.
- Upstream's page route already fetched a page through the source and cached it, so it is the proxy; there is no `/api/image`. Its fetch now goes through `madoyomi.core.reader` (one upstream edit in `Page.getPageImage`). That adds:
  - one fetch per page at a time;
  - one retry, which first gets an expired image URL anew;
  - `SOURCE_FAILED` with the HTTP status;
  - the size cap with LRU eviction (files used in the last 5 minutes stay);
  - `prefetchChapterPages`, the next pages and the next chapter's first ones, 2 requests at a time.
- Progress needs no new table: upstream's `CHAPTER_USER` has the last page, the read flag and the time, through `updateChapter(s)` (Stage 2).
- Settings → **Reader** has the cache cap, what the cache holds and Clear (`readerSettings`, `setReaderSettings`, `pageCache`, `clearPageCache`; `<data dir>/reader-settings.json`).

### Phase 6 — Library features
Details: [phase-6-library-features.md](phases/phase-6-library-features.md)

- Categories, update checker (scheduled), history, unread/unwatched badges.
- Download queue: manga as CBZ; anime via mpv/yt-dlp/ffmpeg using the source headers, with HLS support.
- Backup/restore. Stretch: import Aniyomi/Mihon backup files.

Milestones:
- [x] Categories
- [x] Scheduled update checker + Updates view + badges (Stage 7, the exit run: a scheduled check from a fresh data dir found an episode and a chapter taken out of the database, shown in Updates and as "1 new" beside the badges)
- [x] History
- [x] Download queue (manga CBZ, anime incl. HLS) (Stage 7: an HLS episode and a CBZ chapter opened offline again, from a fresh data dir)
- [x] Backup / restore round-trip (Stage 6: `BackupRestoreApiTest`, and live: `p6-ui` restored into a fresh data dir, compared table by table, phase-6 §10. Stage 7: again from a fresh data dir into a second one, Merge and then Replace)

**Phase 6 done (2026-11-17).** Every exit criterion passes, checked live in `pnpm tauri dev` from a fresh, empty data dir (`p6-exit`), through the UI (phase-6 §10, "Stage 7"):
- the setup: both repos added (type detected), AnimeGG, KickAssAnime and MangaPill installed, 3 anime and 3 manga in the Library, two categories of each kind with a "New entries" flag and a sort;
- **updates:** with an episode and a chapter taken out of the database and the schedule on (every 6 h, shortened to 120 s), the scheduled check found 1 new episode and 1 new chapter (3 entries checked, the 3 completed ones skipped), listed in each kind's Updates, with "1 new" beside the unwatched/unread badges in the Library;
- **offline:** with the sources cut off (a dead SOCKS proxy), "Oshi no Ko" Ep. 2 (KickAssAnime's HLS through ffmpeg, with its separate audio track) played in mpv from its file with its subtitles and audio and was marked watched, and Kingdom's downloaded Chapter 2 (CBZ) read to its last page, while a chapter not downloaded failed with `SOURCE_FAILED`;
- **backup → fresh install → restore:** `p6-exit` backed up in the app and restored into a second fresh data dir after the trust prompt (2 repos, 3 extensions reinstalled, 7 s): the same library, categories with their flags and sorts, marks, positions, history and settings, in the app and table by table; then three changes made after the backup undone by Replace.

Also checked: Stage 6's fixes in the app (the backup list refreshing by itself while a 40 s schedule pruned, a `.tachibk` labelled "Mihon/Aniyomi backup (manga only)"), History, the categories and Downloads after an app restart, the next-episode toast playing a downloaded episode from its file, the audio track's own progress, and no token in any log or backup. The fix round was one wording fix in the restore dialog ("1 settings file"), with a new app test (697 + 17); no server, shell or operation change. The stretch (an Aniyomi backup's anime) was left: a stage of its own, for Phase 8 or later (phase-6 §9 "Stage 7").

The stage plan (agreed at the end of Phase 5) and the phase's decisions are in phase-6 §8–§9:
- **categories: one list per kind** (the user's): each kind's Library has its own categories, as in Aniyomi;
- **History follows the anime/manga switch** (the user's, Stage 2), as every other section;
- **the library update schedule and the toast are off on a fresh install** (the user's, Stage 3): checks run on "Update library" until Settings → Updates turns on every 6, 12 or 24 h, and the Windows toast until it is switched on;
- **manga downloads** (the user's, Stage 4): automatic downloads of new chapters off on a fresh install (Settings → Downloads; the Categories dialog's Downloads flag picks the categories), downloads in the data dir (`downloads/manga`, a folder of one's choice left for Phase 7), and the Downloads screen following the anime/manga switch;
- **the anime download tool** (the user's): ffmpeg for HLS (as Aniyomi), yt-dlp as the fallback when it fails, both bundled in Phase 7; direct files are downloaded by the backend with the source's headers;
- **the backup format** (the user's): gzipped protobuf, `.madoyomi-backup`, upstream's manga backup reused and extended, the field numbers kept compatible for the Aniyomi/Mihon import;
- **backups** (the user's, Stage 6): scheduled backups off on a fresh install, upstream's own daily manga backup off, merge preselected in the restore dialog.

Stage 1 added the categories:
- manga categories are upstream's, with one edit: its GraphQL `updateManga(s)` now puts a newly added manga into the categories flagged for new entries, which only its REST path did (`madoyomi.core.library.MangaNewEntryCategories`, a `// Madoyomi:` call in `MangaMutation.updateMangas`). Anime has Madoyomi's own: `M0072_AnimeCategories` replaces the Phase 1 stub with `ANIME_CATEGORY` (with its Default row) and `ANIME_CATEGORY_ANIME`, `madoyomi.core.anime.AnimeCategories` has upstream's rules (names unique ignoring case, Default protected, new entries into the flagged categories), and the GraphQL mirrors upstream's (`animeCategories`, `createAnimeCategory`, `updateAnimeCategory`, `updateAnimeCategoryOrder`, `deleteAnimeCategory`, `updateAnimeCategories`, `updateAnimesCategories`, `setAnimeCategoryMeta`, `AnimeType.categories`);
- the Library: a tab per category with its count once there is one (Default's only while it holds entries), the open tab remembered per kind, a sort per category in its meta, the Categories dialog (create, rename, move, the new-entries flag, delete with a confirm), and Select with Set categories (tri-state boxes) and Remove from library;
- a Categories button on both details pages, with the same picker;
- tests: 13 new on the server (807), 27 in the app (605 + 12), the live spec 12/12 with 69 documents (it also checks the new-entry flag of each kind); checked live through the API and in `pnpm tauri dev`, after an app restart too (phase-6 §10).

Stage 2 added history and the badges:
- manga history is upstream's `CHAPTER_USER.last_read_at` (no table; upstream's backup already carries it), with one edit: "Mark unread" no longer stamps it (`ChapterMutation.updateChapters`). Anime history is `ANIME_HISTORY` (M0070's stub, one row per episode, no migration), stamped by the player's saves and by `updateEpisode(s)` given a position, and moved with a re-listed episode. `animeHistory`/`mangaHistory` (one item per entry, newest first, with a `resume` target), `delete…History` and `clear…History` (the marks and positions stay), `AnimeType.unwatchedCount`/`lastSeenAt` (upstream's `unreadCount` for manga);
- the History view (a route and a sidebar entry): by day, Resume (the reader at the page, or the episode's picker with its resume point; the next one when finished), Remove, Clear all; the library cards' unwatched/unread badge and the sorts by that count and by last watched/read, kept current after marks, reader saves and playback;
- tests: 6 new on the server (813), 20 in the app (625 + 13), the live spec 13/13 with 75 documents; checked live in `pnpm tauri dev` after playing an episode in mpv and reading chapters (phase-6 §10). The "badges" milestone stayed open for Stage 3's "new" badges and Updates view.

Stage 3 added the update checker:
- Madoyomi's own check for both kinds (`madoyomi.core.library.LibraryUpdater`) in place of upstream's manga-only `Updater`, whose 12 h schedule is off (one upstream edit, `Updater.scheduleUpdateTask`). It uses upstream's category rule (`includeInUpdate`; one category's check ignores it) and upstream's chapter diff, and `AnimeDetails`' episode diff (a Seasons entry: its season list and the seasons fetched before). It checks one entry at a time per source, 1 s apart, with at most 6 sources at once (`maxSourcesInParallel`). It skips completed entries (a setting; skipping entries with unwatched/unread items or not started is there too, off). New items are the ones it inserted, and an entry's first fetch isn't news;
- the settings in `<data dir>/library-update.json` (the interval, the skips, the toast). The schedule (upstream's `HAScheduler`) counts from the last scheduled check, so a restart doesn't push it back. `M0073_LibraryUpdateMarks` keeps the entries' "new" marks until they are opened. GraphQL: `libraryCheck`, `startLibraryUpdate`, `stopLibraryUpdate`, the `libraryUpdates` subscription (the WS "library.updates" event), `libraryUpdateSettings`, `setLibraryUpdateSettings`, `animeUpdates`/`mangaUpdates`, `updateMarks`, `clearUpdateMarks`;
- the Updates view (a route and a sidebar entry with the kind's count of new items): by day, newest first, Play/Read opening the item and clearing the mark, Mark all seen, the last check's result with the entries it couldn't reach, Update library with Stop. The Library has Update library and "Update <tab>", and "N new" on the cards; a details page clears its mark. Settings → Updates; the Categories dialog's Updates and Downloads flags (Default's too); the Windows toast through `tauri-plugin-notification`;
- tests: 8 new on the server (821), 27 in the app (652 + 14), the live spec 14/14 with 85 documents, `cargo test` 17; checked live in `pnpm tauri dev` with scheduled checks every 150 s on `p6-ui`, which brought back an episode and a chapter taken out of the database; the user saw the toast (phase-6 §10).

Stage 4 added manga downloads:
- Madoyomi's own download queue for both kinds (`madoyomi.core.downloads`, `M0074_Downloads`), in place of upstream's in-memory manga `DownloadManager`. It survives a restart with its order, pauses and failures. At most 3 at once and 1 per source, 1 s apart; two automatic retries, then FAILED with Retry; pause, resume, cancel and reorder. Chapters are staged outside the page cache and written as CBZ with upstream's `ComicInfo.xml` at upstream's path, now `downloads/manga`, so upstream's reader reads them, offline too. New chapters are auto-downloaded after an update check when it is on. Three upstream edits: the folder name, upstream's own auto-download off, and (found by the live check) a CBZ page's stream that owns its archive, so a page is no longer cut off by `ClosedChannelException` when GC finalizes the `ZipFile`;
- GraphQL: `downloadQueue`, `completedDownloads`, `downloadSettings`, `enqueueDownloads`, `pauseDownloads`, `resumeDownloads`, `retryDownloads`, `cancelDownloads`, `reorderDownload`, `deleteDownloads`, `clearCompletedDownloads`, `setDownloadSettings`, the `downloadsChanged` subscription;
- the Downloads screen (the queue live, the completed list), the chapter list's Download, Cancel, Retry, the selection's Download and Delete downloads, "Download next N unread", "↓ N" on the library cards, the sidebar's count, Settings → Downloads;
- tests: 15 new on the server (836, `CbzPageStreamTest` from the live check), 19 in the app (671 + 15), the live spec 15/15 with 98 documents (a 92-page MangaPill chapter downloaded as a 27.6 MB CBZ in 6–8 s and read back through the backend);
- checked live in `pnpm tauri dev` on `p6-ui`: the chapter list's actions, the Downloads screen live (pause, resume, move, cancel, all of them, the completed list), two app restarts mid-download (the queue back in order, a pause kept), Settings → Downloads, and with the sources cut off (a dead SOCKS proxy) downloaded chapters read to their end in the paged and webtoon modes while a chapter not downloaded failed (phase-6 §10). The CBZ fix was rechecked in the app at the start of Stage 5 (no `ClosedChannelException`).

Stage 5 added anime downloads:
- episodes join Stage 4's queue (`AnimeEpisodeDownloader`): the video resolved right before the download through the extension, picked by the player settings' preferred server and quality; its kind read from the first answer. A direct file is streamed by the backend with the video's headers and resumed by range; HLS (and DASH) goes through `ffmpeg -headers … -c copy` into MKV, with yt-dlp when ffmpeg fails and both failures named. The headers are quoted for Windows (Java doesn't escape a `"`), and segments disguised as images are let through. Subtitles and the external audio tracks are saved beside the file. Files at `downloads/anime/<source>/<title>/<episode>.<ext>`, recorded in `EPISODE_DOWNLOAD` (`M0075_AnimeDownloads`, which also gives the queue rows a progress unit and size: bytes, the episode's time or segments). No upstream edit;
- ffmpeg and yt-dlp found as mpv is (Settings → Downloads, `MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` from the shell, PATH), with a check; their processes killed on pause, cancel and stop;
- a downloaded episode plays its file in mpv in Phase 4's session (progress, the watched mark, the next episode), offline too; Stream… keeps the picker; automatic downloads of new episodes after a library check;
- GraphQL: `enqueueDownloads`/`deleteDownloads` for anime, `progressUnit`/`totalBytes`, `downloadTools`, the tool paths in the settings, `playDownloadedEpisode`, `EpisodeType.downloaded`, `AnimeType.downloadCount`;
- the app: the episode list's download actions, the Downloads screen's anime view with Play, "↓ N" on anime cards, the tools in Settings → Downloads;
- tests: 11 new on the server (847), 9 in the app (680 + 16), the live spec 16/16 with 100 documents (KickAssAnime's HLS with its audio track through ffmpeg, 127 MB in 382 s; AnimeGG's direct file, 65.5 MB in 96 s), `cargo test` 18;
- checked live in `pnpm tauri dev` on `p6-ui`: Stage 4's CBZ fix first (no `ClosedChannelException`), then "Oshi no Ko" Ep. 2 from KickAssAnime downloaded, paused, resumed and cut off by an app restart, then played offline in mpv from its file with its subtitles and audio (the sources cut off by a dead SOCKS proxy), marked watched (phase-6 §10). The check found that KickAssAnime's audio comes as separate tracks, so the first download had none; fixed in the stage. This completes the "Download queue" milestone and the offline exit criterion (to be rechecked by the exit run).

Stage 6 added backup and restore:
- `.madoyomi-backup` files (`madoyomi.core.backup`, no new table): upstream's `Backup` fields and models for the manga part (written and read by upstream's handlers), Madoyomi's from field 20000 up for the anime library with its seasons, episodes, positions, history and categories, the manga categories' flags, the repos, the certificates the user trusted, the installed extensions (not the APKs), the sources' preferences and the backend's settings files. Not upstream's server and user settings (passwords in plain text), the downloads, the queue, the update marks or the device's localStorage (phase-6 §9);
- Back up now, a schedule (off by default, the user's; daily or weekly, the newest N kept), files in `<data dir>/backups`; upstream's own daily manga backup off (one upstream edit, `ProtoBackupExport.scheduleAutomatedBackupTask`);
- restore: the file checked first (what it holds, the sources not installed), merge (the default, the user's) or replace, and a trust prompt for the repos (adding one trusts its key), the certificates and the extensions to reinstall from their repos; anime mapped by source and URL, manga through upstream's restore; the progress over `madoyomiRestoreStatusChanged`. A Mihon/Suwayomi `.tachibk` restores its manga part; Aniyomi's anime part isn't read (the stretch, left);
- Settings → Backup and the restore dialog in the app;
- tests: 6 new on the server (853), 16 in the app (696 + 17), the live spec 17/17 with 109 documents; checked live in `pnpm tauri dev`: `p6-ui` backed up and restored into a fresh data dir after the trust prompt (2 repos, 5 extensions reinstalled), the same library, categories, progress, history and settings, compared in the app and table by table; a 40 s schedule keeping 3 (phase-6 §10).

Stage 7 was the exit run (above).

### Phase 7 — Packaging & release
Details: [phase-7-packaging-release.md](phases/phase-7-packaging-release.md) · Stage plan (agreed 2026-11-17, phase-7 §8): runtime and binaries, installer, logs and diagnostics, auto-update, CI release, exit run. **In progress: Stage 2 of 6 done** (2026-11-17).

Stage 1 (the runtime and binaries, done): `tools/jlink.ps1` links a 59.99 MiB runtime (22 modules: jdeps' plus `jdk.crypto.ec`, `jdk.localedata`, `jdk.charsets`, `jdk.zipfs`, without six debugger and tooling modules that nothing runs), checked by `tools/lib/JreProbe.java`; on it the compat harness gave anime 18/20 (as recorded) and manga 8/10 (kagane's site went behind Cloudflare, the same on the JDK), smoke 24/24 and the live spec 17/17. `tools/binaries.json` pins mpv 0.41.0, ffmpeg 9.0.2 and yt-dlp 2026.10.26 with their SHA-256, which `tools/fetch-binaries.ps1` checks. `tools/build-release.ps1` bundles them through `tauri.release.conf.json`. A release build run with nothing on PATH played, read and downloaded an HLS episode with the bundled runtime and tools (phase-7 §10).

Stage 2 (the installer, done): one version in `VERSION`, checked or written into `package.json`, `tauri.conf.json`, `Cargo.toml`/`Cargo.lock` and the server's `Constants.kt` by `tools/version.ps1` (`build-release.ps1` fails on a mismatch); the NSIS installer per-user into `%LOCALAPPDATA%\Madoyomi` without admin, publisher "Madoyomi contributors", the uninstaller's box removing the whole data dir when ticked (`installer/hooks.nsh`, "Delete my library, settings and downloads too"); the MSI with its upgrade code pinned; the license texts of every bundled tool and of the libraries in mpv's build pinned by SHA-256 and installed beside them, with `LICENSE`, `NOTICE` and `THIRD_PARTY.md`. The user decided: no code signing for now, Windows-only, the MSI kept (installed, upgraded 0.1.0 → 0.1.1 and uninstalled on the dev desktop by the user after the stage, one entry and the data kept throughout; the clean machine in Stage 6), "remove data" removes everything, Tauri's icon for now. On the dev desktop the NSIS installer installed (silently and through its UI), the installed app ran with nothing on PATH (read, played), upgraded 0.1.0 → 0.1.1 keeping the data, and uninstalled keeping it or, on a scratch data dir, removing it (phase-7 §10).

- `jlink` minimal JRE; bundle `mpv.exe`; Tauri NSIS/MSI installer.
- Tauri auto-updater for the app; the backend updates with it.
- Crash/log collection (local log files, an "export logs" button).

Milestones:
- [x] jlink JRE + bundled mpv (and ffmpeg/yt-dlp) (Stage 1)
- [ ] NSIS/MSI installers work on a clean machine without Java (Stage 2: the NSIS installer on the dev desktop with nothing on PATH, and the MSI installed, upgraded and uninstalled there by the user; the clean machine is Stage 6's)
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
| Video URLs require headers/cookies | 403s in the player | Always pass `Video.headers` to mpv; proxy fallback. Since Phase 4 Stage 1 every header reaches mpv verbatim through a per-session include file (S4's forms, one `http-header-fields-append` each), checked live with AnimeGG (Referer required) and KickAssAnime (7 headers), and in Stage 2 for the subtitle and audio tracks added over IPC too; Stage 4 measured it through the header proxy: all 2 of AnimeGG's and 7 of KickAssAnime's headers arrived verbatim (commas and semicolons included), so the proxy fallback is deferred until a source needs a header mpv can't send (phase-4 §9). The Stage 6 format matrix measured them again on every row: AnimeGG's 2 on its MP4 and on an MKV's requests, KickAssAnime's 7 on its HLS playlist, all verbatim. Expired URLs are fetched again once (Stage 4), at once and mid-play, for MP4 and HLS (Stage 6), and so is a stream that never plays a frame |
| Very tall webtoon pages and long strips | Stutter, blank pages or memory growth in the webtoon mode | Phase 5 Stage 3: only the pages near the viewport are in the DOM and loaded (by pixels, a screen behind and two ahead), each decoded before it shows; pages that leave are revoked. Live, 103 pages scrolled with no frame over 50 ms and no blank frame, and the memory after the chapter was where it started; 800×30000 pages are shown whole (about 96 MB decoded each, 373 MB in the renderer with two around the viewport, released on leaving). The exit run (Stage 6, the dev desktop) read the 103-page chapter cold with no frame over 50 ms (the longest 16 ms) and no blank frame, 5 chapters in one go (472 pages) with the memory flat (512 MB working set before, 514–518 MB at each chapter's end, 479 MB after leaving), and the crossings between chapters now show no blank frame (the neighbour's opening pages held decoded ahead). Not measured on a mid-range laptop (the user's decision). Tiling or downscaling only if a source's pages prove bigger (phase-5 §7, §10) |
| Manga page images need the source's headers and cookies, and their URLs can expire | Blank pages in the reader | Every page goes through upstream's page route, which fetches it with the source's own client (headers, cookie jar, rate limits); a loopback test checks the Referer and a cookie (Phase 5 Stage 1). A page that fails with HTTP 401/403/404/410 gets its image URL anew once (its own URL resolved again, else the chapter's page list fetched again) before `SOURCE_FAILED` reaches the reader; a transient failure is retried once. Live through the backend on MangaPill, Webtoons (a 103-page chapter) and MangaDex; a MangaDex page read 16 minutes after its chapter opened still answered without a retry, so the expired-URL path is covered by tests only so far (phase-5 §10). Since Stage 2 the reader shows a failed page's error with the source's HTTP status and a retry, and fetches pages that failed while the backend was away again once it is back (live, with the backend killed mid-chapter) |
| Extensions are arbitrary JVM code | Security | Trust prompt by signing cert; only user-added repos; consider a separate JVM process per extension type. Since Phase 3 Stage 3 the UI's trust prompt shows the certificate hash and warns that a trusted extension runs with the user's rights, and removing a repo asks first and says which extensions stop loading |
| R8-optimised APKs that dex2jar mistranslates (e.g. Keiyoushi's removed constructors and merged classes) | Extensions fail to load (`VerifyError`) | A bytecode repair pass after dex2jar (`Dex2jarRepair`: constructors restored, frames recomputed with the APK's class hierarchy); the Phase 2 compat scan covers a 10-extension manga sample. It found two more patterns there (a super call that skips a removed constructor; a `new T` dex2jar leaves unfolded), fixed in Stage 6: all 30 APKs verify. Rerun `tools/compat-test/scan.ps1` when the lists are refreshed |
| Protobuf index schema undocumented | Can't list manga extensions | Reverse-engineer from Mihon's client source; JSON fallback if one exists |
| License mixing | Suwayomi is MPL-2.0, Aniyomi code is Apache-2.0 | Madoyomi is MPL-2.0; keep upstream headers; add a NOTICE/THIRD_PARTY file |
| Anime downloads: HLS streams, external tools | An episode that won't download; a missing or old ffmpeg/yt-dlp | Phase 6 Stage 5: ffmpeg first with the source's headers on every segment and key, segments disguised as images let through, yt-dlp when ffmpeg fails and both failures named; the headers quoted for Windows' command line. A direct file resumes by range; HLS doesn't (a retry downloads the stream again). External audio and subtitle tracks are saved beside the file (KickAssAnime's audio is separate). The tools are found as mpv is, with a check in Settings → Downloads; Phase 7 Stage 1 bundles both, pinned (ffmpeg 9.0.2, yt-dlp 2026.10.26), and a release build downloaded an HLS episode with them. Measured live: KickAssAnime's episode with its audio through ffmpeg (127 MB at 360p in 382 s; 480 MB at 1080p in about 3 min in the app) and AnimeGG's direct file (65.5 MB in 96 s). Not seen live: yt-dlp taking over, DASH |
| Downloads hammer the sources or fill the disk | Rate limits, bans; a full disk | Phase 6 Stage 4: at most 3 downloads at once, 1 per source, two of a source 1 s apart, a chapter's pages one at a time on top of each extension's rate limits; two retries with a backoff, then FAILED; automatic downloads off by default (the user's). Measured live through the API: a 92-page MangaPill chapter in 6–8 s (27.6 MB); in the app, Kingdom's chapters one at a time with the limits shown live, a failure retried twice then FAILED with Retry (phase-6 §10). Not measured: a long queue (hundreds of chapters) against one site; no disk-space check before a download |
| Backups: secrets, restoring extensions | A password in a backup file; a restore that runs code nobody re-approved | Phase 6 Stage 6: upstream's server settings (the proxy, login and database passwords) are left out, and so are an extension's private preference keys; the API token is never stored. A source's own settings (which can hold a site login) are carried as the extension stored them, as in Mihon. A restore adds repos, trusts certificates and installs extensions only after the trust prompt, and only those left checked; checked live with no token or password string in the file. The exit run (Stage 7) checked it again: a restore into a fresh data dir with the trust prompt, Merge and Replace, and no token, password or proxy string in four backups |
| Library update checks hammer the sources | Rate limits, bans, slow sites | Phase 6 Stage 3: one entry at a time per source, 1 s apart, at most 6 sources at once (`maxSourcesInParallel`), on top of each extension's own rate limits; completed entries and "fetch once" ones skipped; the schedule off by default (the user's), and upstream's own 12 h manga schedule turned off so there is only one. Measured live: six entries of one source 1.2 s apart (phase-6 §10). Not measured on a large library |
| The jlink runtime misses a module an extension reaches at runtime (reflection, ServiceLoader, a locale or charset) | An extension fails only in the installed app, not in dev | Phase 7 Stage 1: jdeps' modules plus `jdk.crypto.ec`, `jdk.localedata` (all locales), `jdk.charsets` and `jdk.zipfs`; six modules jdeps lists but nothing runs are left out, each with its reason in `tools/jlink.ps1`. `tools/lib/JreProbe.java` runs on every new runtime (JS, dates in 7 locales, charsets, zipfs, TLS, imaging, prefs, JDBC, JNDI) and fails the build. The compat harness, smoke and the live spec ran on it with the JDK's results (manga's one new failure, kagane, is its site's Cloudflare, the same on the JDK). The runtime is at the 60 MB target (59.99 MiB), so a JDK update may cross it: the script fails, and `--include-locales` or the target is the choice then. Not measured: an extension needing Duktape (upstream's stub has no engine on any runtime), `java.net.http` or a module left out |
| Bundled third-party binaries: where they come from, their licenses | A tampered or vanished download; GPL obligations | Phase 7 Stage 1: versioned releases with fixed URLs (the mpv project's 0.41.0 build, gyan.dev's ffmpeg 9.0.2 essentials, yt-dlp 2026.10.26) pinned by SHA-256 in `tools/binaries.json`, matched with GitHub's digests and the publishers' sums; `tools/fetch-binaries.ps1` fails on a mismatch and the downloads are cached in `tools/bin/downloads`. mpv and ffmpeg are GPL and run as separate programs. Stage 2: the license texts of each tool and of the 15 libraries in mpv's build pinned by SHA-256 in `binaries.json` and installed beside the tools, `THIRD_PARTY.md` linking each source at the build's version. mpv's build clones several libraries at their newest commit, so their exact source is only known for FFmpeg; whether releases attach the corresponding source is open (§7, Stage 5). Installed: 461.6 MiB in 255 files (installers 275.1 MiB NSIS, 301.1 MiB MSI) |
| Unsigned installers: SmartScreen, antivirus | "Windows protected your PC" on every new version; a user who gives up | Phase 7 Stage 2: no signing for now (the user's decision). Since 2024 no certificate skips SmartScreen (EV no longer does); a signed file builds reputation that carries to the next version, an unsigned one starts again each time. The release notes and `RELEASING.md` (Stage 5) say "More info → Run anyway"; SignPath Foundation (free for open source, once the repo is public) or a certificate can be added to the CI later. Not seen yet: a downloaded installer (the local builds had no Mark of the Web; Stage 6) |

---

## 7. Open decisions

- [x] Fork Suwayomi-Server, or write a fresh backend reusing its extension-host code? **Fork** (phase-0 §9; confirmed by the S1 run and the S3 harness running on Suwayomi's runtime unchanged).
- [x] REST, or extend Suwayomi's GraphQL? **Extend GraphQL**, keep REST for binary endpoints (phase-0 §9).
- [x] mpv in a separate window (MVP) vs. embedded (later)? **A separate window for the MVP**; embedding with `--wid` stays a stretch goal after the Phase 4 exit run. **The backend owns the mpv process** (phase-4 §9, Stage 1).
- [x] One JVM process for both extension types, or isolated processes? **One JVM** (shared network/DI/stub layer). Crash isolation is deferred: an in-process extension can't be contained without a separate JVM anyway, so the trust gate is the containment. Revisit if Phase 2's compat harness shows hard crashes (phase-1 §8, §9). **Verdict (Phase 2 Stage 6): one JVM stays.** Two harness runs of the top 20 in one JVM had no abort, bounded heap and threads. The class loaders kept after uninstall went from 15 to 1 (a connection reanime keeps open; phase-1 §8, phase-2 §10).
- [x] UI components and theme? **Headless primitives (Reka UI) with our own styles; dark and light themes that follow Windows; Fluent-like tokens** (phase-3 §9, Stage 1). The window keeps the **native Windows title bar** (Snap Layouts, caption buttons; phase-3 §9, Stage 2).
- [x] Name: Madoyomi · License: MPL-2.0
- [x] Library categories: one list for both kinds, or one per kind? **One per kind**, as in Aniyomi: upstream's manga categories (their new-entry flag now honoured through GraphQL too), Madoyomi's own for anime (phase-6 §9, Stage 1).
- [x] Anime downloads: ffmpeg, yt-dlp or both? **Both, ffmpeg the default**, yt-dlp as the fallback (phase-6 §8–§9, Stage 1).
- [x] Backup format? **Gzipped protobuf, `.madoyomi-backup`** (phase-6 §8–§9, Stage 1).
- [x] Library update schedule and toast by default? **Both off** on a fresh install, turned on in Settings → Updates (phase-6 §9, Stage 3).
- [x] Manga downloads by default? **Automatic downloads off, the folder in the data dir, the Downloads screen following the anime/manga switch** (phase-6 §9, Stage 4).
- [x] Backups by default? **The schedule off, upstream's own daily backup off, merge preselected** (phase-6 §9, Stage 6).
- [x] Windows-only, or keep macOS/Linux possible (the architecture already allows it)? **Windows-only** (the user's, phase-7 §8–§9, Stage 2): CI builds and tests Windows only; code that is already portable stays so. Code signing, closed with it: **none for now**.
- [ ] GPL corresponding source in releases: link the upstream sources at the builds' versions (as `THIRD_PARTY.md` does now), or attach a source archive to each release? Phase 7 Stage 5 (the CI release; phase-7 §10, Stage 2).
- [ ] The app icon: still Tauri's default (the user's, phase-7 §9, Stage 2); for a later stage.

---

## 8. First tasks (start here)

These are the Phase 0 spikes; see [phase-0-spikes.md](phases/phase-0-spikes.md) for the detailed steps. **All done as of 2026-11-03.** [Phase 1](phases/phase-1-backend-foundation.md) and [Phase 2](phases/phase-2-anime-sources.md) are done too (2026-11-06), and so is [Phase 3](phases/phase-3-frontend-mvp.md) (2026-11-07, seven stages: the shell and sidecar, the API layer, Repos and Extensions, Browse and Source, Details and Library, Settings, and the exit run).

[Phase 4](phases/phase-4-playback.md) (playback in mpv) is done too (2026-11-12, six stages). Stage 1 settled mpv in its own window, owned by the backend (§7), and added the launcher and Play. Stage 2 added the IPC client, progress saving, the watched mark and the playback events. Stage 3 added the video picker, the resume prompt, live progress and the Watched badge in the episode list, and bulk marks (`updateEpisodes`). Stage 4 added the next-episode toast with auto-play and the retry on expired URLs with the next video as the fallback (the header proxy deferred by measurement). Stage 5 added the player settings (the watched threshold, the preferred quality and server with the sorting, the subtitle and audio language, the mpv path), kept by the backend. Stage 6, the exit run, passed the format matrix and every exit criterion from a fresh data dir.

[Phase 5](phases/phase-5-manga-reader.md) (the manga reader) is done too (2026-11-14, six stages). Stage 1 agreed the stage plan and the two decisions (the default reader mode: the manga's own, else webtoon by its genres, else paged RTL; the page cache: 1 GB, in the data dir), and added the image pipeline around upstream's page route: one fetch per page, the retry with expired URLs got anew, `SOURCE_FAILED`, the cache's cap with LRU eviction, `prefetchChapterPages`, and Settings → Reader. Stage 2 added the reader with the paged LTR/RTL mode, progress and resume, and reading from the chapter list. Stage 3 added the webtoon mode (virtualized, heights from each page's ratio, a pixel load window, the mode switch and the page gap) with the first measurement on a 100+ page chapter. Stage 4 added the double-page mode (spread detection, "shift by one"), zoom and pan in the paged modes, and the seamless next-chapter transition in every mode. Stage 5 added the reader settings: the mode and direction per manga in its meta, the default mode by genre or by a webtoon source's name, and on this device the defaults, the page fit, the strip width, the page gap, the background and the preload count. Stage 6, the exit run, passed every exit criterion from a fresh data dir (the scroll measured on the dev desktop, by the user's decision), with a fix round for blank frames at chapter crossings, a pan kept across a fit change and a double-page place that moved forward on a reopen.

[Phase 6](phases/phase-6-library-features.md) (library features) is done too (2026-11-17, seven stages). Its stage plan is in phase-6 §8: 1. Categories, with the two §8 decisions (done: one category list per kind; ffmpeg for anime downloads with yt-dlp as the fallback; gzipped protobuf backups as `.madoyomi-backup`); 2. History and the unread/unwatched badges (done: history from the watched and read progress, the History view following the anime/manga switch with resume, delete and clear, the counts on the library cards and two new sorts); 3. the update checker and the Updates view (done: one check for both kinds, scheduled (off by default) and manual, per category, one entry at a time per source, completed entries skipped, the "new" marks, the Updates view, the `libraryUpdates` event and the Windows toast, off by default); 4. manga downloads (done: one queue for both kinds that survives a restart, with limits, retries, pause, resume, cancel and reorder, CBZ with `ComicInfo.xml` read offline, the Downloads screen and the chapter list's actions, checked live with the sources cut off); 5. anime downloads (done: the video resolved right before the download by the player settings, direct files with their headers and resumed by range, HLS through ffmpeg with yt-dlp as the fallback, subtitles and external audio tracks beside the file, mpv playing the local file in the same session, the episode list's actions and the anime Downloads view, checked live offline); 6. backup and restore (done: `.madoyomi-backup` files of both kinds with the repos, trusted certificates, extensions, source settings and settings files, Back up now and a schedule (off by default) keeping the last N, upstream's own daily backup off, restore with merge (the default) or replace after a trust prompt that reinstalls the extensions, a `.tachibk`'s manga part, checked live into a fresh data dir); 7. the exit run (done: every §6 criterion from a fresh data dir, the scheduled update check, an HLS episode and a CBZ chapter offline, a backup restored into a second fresh data dir with Merge and Replace; one wording fix; the Aniyomi anime import left).

**[Phase 7](phases/phase-7-packaging-release.md) (packaging and release) is in progress.** Its stage plan (agreed at the end of Phase 6) is in phase-7 §8: 1. the runtime and binaries (a jlink JRE, pinned mpv, ffmpeg and yt-dlp, the production paths); 2. the installer (NSIS and MSI, one version, the licenses, the code-signing and Windows-only decisions); 3. logs and diagnostics (rolling logs, Export logs, the crash dialog, the downloads folder of one's choice); 4. the auto-updater; 5. the CI release; 6. the exit run on a clean Windows 11 machine. Stage 1 is done (2026-11-17): a 59.99 MiB jlink runtime checked by a probe, the compat harness, smoke and the live spec; pinned mpv 0.41.0, ffmpeg 9.0.2 and yt-dlp 2026.10.26 with their SHA-256; `tools/build-release.ps1` and the release paths, checked live with nothing on PATH (phase-7 §10). Stage 2 is done (2026-11-17): one version (`VERSION`, `tools/version.ps1`), the per-user NSIS installer installed, upgraded and uninstalled on the dev desktop (keeping the data, or removing it when asked), the MSI with a fixed upgrade code (installed, upgraded and uninstalled on the dev desktop by the user, keeping the data), the license texts in the installer; no signing for now, and Windows-only (phase-7 §9–§10). **Next: Stage 3, logs and diagnostics** (rolling logs for the backend, the shell and mpv, Export logs, the repeated-crash dialog, and the downloads folder of one's choice).

1. Clone Suwayomi-Server, run it, add the Keiyoushi repo, and confirm manga works.
2. Download one yuzono APK, dex2jar it, and inspect which `extensions-lib` classes it references.
3. Write a tiny Kotlin harness that loads that jar and calls `getPopularAnime(1)`.
4. Play one returned video in mpv with its headers.
5. ~~Scaffold Tauri 2 + Vue 3 in `madoyomi/app`~~ (done in `547f422`), then spawn a dummy JVM sidecar.
