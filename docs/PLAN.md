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
| Video URLs require headers/cookies | 403s in the player | Always pass `Video.headers` to mpv; proxy fallback. Since Phase 4 Stage 1 every header reaches mpv verbatim through a per-session include file (S4's forms, one `http-header-fields-append` each), checked live with AnimeGG (Referer required) and KickAssAnime (7 headers), and in Stage 2 for the subtitle and audio tracks added over IPC too; Stage 4 measured it through the header proxy: all 2 of AnimeGG's and 7 of KickAssAnime's headers arrived verbatim (commas and semicolons included), so the proxy fallback is deferred until a source needs a header mpv can't send (phase-4 §9). The Stage 6 format matrix measured them again on every row: AnimeGG's 2 on its MP4 and on an MKV's requests, KickAssAnime's 7 on its HLS playlist, all verbatim. Expired URLs are fetched again once (Stage 4), at once and mid-play, for MP4 and HLS (Stage 6), and so is a stream that never plays a frame |
| Very tall webtoon pages and long strips | Stutter, blank pages or memory growth in the webtoon mode | Phase 5 Stage 3: only the pages near the viewport are in the DOM and loaded (by pixels, a screen behind and two ahead), each decoded before it shows; pages that leave are revoked. Live, 103 pages scrolled with no frame over 50 ms and no blank frame, and the memory after the chapter was where it started; 800×30000 pages are shown whole (about 96 MB decoded each, 373 MB in the renderer with two around the viewport, released on leaving). The exit run (Stage 6, the dev desktop) read the 103-page chapter cold with no frame over 50 ms (the longest 16 ms) and no blank frame, 5 chapters in one go (472 pages) with the memory flat (512 MB working set before, 514–518 MB at each chapter's end, 479 MB after leaving), and the crossings between chapters now show no blank frame (the neighbour's opening pages held decoded ahead). Not measured on a mid-range laptop (the user's decision). Tiling or downscaling only if a source's pages prove bigger (phase-5 §7, §10) |
| Manga page images need the source's headers and cookies, and their URLs can expire | Blank pages in the reader | Every page goes through upstream's page route, which fetches it with the source's own client (headers, cookie jar, rate limits); a loopback test checks the Referer and a cookie (Phase 5 Stage 1). A page that fails with HTTP 401/403/404/410 gets its image URL anew once (its own URL resolved again, else the chapter's page list fetched again) before `SOURCE_FAILED` reaches the reader; a transient failure is retried once. Live through the backend on MangaPill, Webtoons (a 103-page chapter) and MangaDex; a MangaDex page read 16 minutes after its chapter opened still answered without a retry, so the expired-URL path is covered by tests only so far (phase-5 §10). Since Stage 2 the reader shows a failed page's error with the source's HTTP status and a retry, and fetches pages that failed while the backend was away again once it is back (live, with the backend killed mid-chapter) |
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

[Phase 4](phases/phase-4-playback.md) (playback in mpv) is done too (2026-11-12, six stages). Stage 1 settled mpv in its own window, owned by the backend (§7), and added the launcher and Play. Stage 2 added the IPC client, progress saving, the watched mark and the playback events. Stage 3 added the video picker, the resume prompt, live progress and the Watched badge in the episode list, and bulk marks (`updateEpisodes`). Stage 4 added the next-episode toast with auto-play and the retry on expired URLs with the next video as the fallback (the header proxy deferred by measurement). Stage 5 added the player settings (the watched threshold, the preferred quality and server with the sorting, the subtitle and audio language, the mpv path), kept by the backend. Stage 6, the exit run, passed the format matrix and every exit criterion from a fresh data dir.

[Phase 5](phases/phase-5-manga-reader.md) (the manga reader) is done too (2026-11-14, six stages). Stage 1 agreed the stage plan and the two decisions (the default reader mode: the manga's own, else webtoon by its genres, else paged RTL; the page cache: 1 GB, in the data dir), and added the image pipeline around upstream's page route: one fetch per page, the retry with expired URLs got anew, `SOURCE_FAILED`, the cache's cap with LRU eviction, `prefetchChapterPages`, and Settings → Reader. Stage 2 added the reader with the paged LTR/RTL mode, progress and resume, and reading from the chapter list. Stage 3 added the webtoon mode (virtualized, heights from each page's ratio, a pixel load window, the mode switch and the page gap) with the first measurement on a 100+ page chapter. Stage 4 added the double-page mode (spread detection, "shift by one"), zoom and pan in the paged modes, and the seamless next-chapter transition in every mode. Stage 5 added the reader settings: the mode and direction per manga in its meta, the default mode by genre or by a webtoon source's name, and on this device the defaults, the page fit, the strip width, the page gap, the background and the preload count. Stage 6, the exit run, passed every exit criterion from a fresh data dir (the scroll measured on the dev desktop, by the user's decision), with a fix round for blank frames at chapter crossings, a pan kept across a fit change and a double-page place that moved forward on a reopen.

**[Phase 6](phases/phase-6-library-features.md) (library features) is next.** Its stage plan, agreed with the user at the end of Phase 5 (phase-5 §9 "Stage 6"), is: 1. Categories, with the two §8 decisions (the anime download tool, the backup format); 2. History and the unread/unwatched badges; 3. the update checker and the Updates view; 4. manga downloads (CBZ, the reader reads them); 5. anime downloads (direct files and HLS, subtitles, mpv plays them); 6. backup and restore; 7. the exit run. Stage 1 writes the plan into phase-6 §8 and starts with Categories.

1. Clone Suwayomi-Server, run it, add the Keiyoushi repo, and confirm manga works.
2. Download one yuzono APK, dex2jar it, and inspect which `extensions-lib` classes it references.
3. Write a tiny Kotlin harness that loads that jar and calls `getPopularAnime(1)`.
4. Play one returned video in mpv with its headers.
5. ~~Scaffold Tauri 2 + Vue 3 in `madoyomi/app`~~ (done in `547f422`), then spawn a dummy JVM sidecar.
