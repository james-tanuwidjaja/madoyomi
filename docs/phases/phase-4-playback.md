# Phase 4 — Playback

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 3](phase-3-frontend-mvp.md) · Next: [Phase 5](phase-5-manga-reader.md)

## 1. Goal

Watch episodes reliably: pick a video/server/quality from `getVideoList`, launch mpv with the right headers, subtitles and audio tracks, track progress over JSON IPC, resume where the user stopped, mark episodes watched, and go to the next episode. mpv runs in its own window for the MVP; embedding it in the app window is a stretch goal.

## 2. Depends on / inputs

- Phase 2: `GET /api/episodes/{id}/videos` returns `Video` with headers, subtitle and audio tracks.
- Phase 3: Details screen with the episode list.
- Phase 0 S4: working mpv command line and named-pipe IPC notes.
- mpv: the path in Settings → Player if set (Stage 5), else in dev `tools/bin/mpv/mpv.exe` if present, else `mpv.exe` on PATH (bundled in Phase 7; §9 "Where mpv comes from").

## 3. Deliverables

- `server/core/player/`: `MpvLauncher`, `MpvIpcClient` (named pipe, JSON IPC), `PlaybackSession` manager.
- `player/`: `mpv.conf`, `input.conf`, optional `uosc` scripts, and an IPC contract doc (`player/IPC.md`).
- DB: `episode_progress` (last position, duration, watched flag, updated at).
- API: `POST /api/player/play`, `POST /api/player/{session}/command`, `GET /api/player/{session}`; WS events `playback.progress`, `playback.ended`, `playback.error`.
- UI: video picker dialog, "Resume from mm:ss" / "Start over", watched indicators, next-episode prompt.
- Settings: watched threshold (default 85%), preferred quality, preferred subtitle/audio language, mpv path override.

## 4. Detailed steps

### Backend — launching mpv
1. `MpvLauncher.build(video, options)` (built as `MpvCommand`, §9): URL, `--referrer`/`--user-agent` for those two headers and **one `--http-header-fields-append="Name: value"` per other header** (Phase 0 S4: the comma-joined `--http-header-fields` splits values at `,`), one `--sub-files-append` per subtitle, one `--audio-files-append` per audio track, `--force-media-title`, `--start=<resume>`, `--input-ipc-server=\\.\pipe\madoyomi-mpv-<session>`, `--config-dir=<player dir>`, `--ytdl=no` always, and lib-16 `Video.mpvArgs`. The source's values go into a per-session `--include` file rather than the command line (§9, Stage 1).
2. Spawn mpv as a child process. Track the PID and kill it on backend shutdown.
3. One active session by default; starting a new one closes the old one (configurable later).

### Backend — IPC & progress
4. `MpvIpcClient`: connect to the named pipe with retries, send `{ "command": [...] , "request_id": n }`, read line-delimited JSON, support `observe_property`.
5. Observe `time-pos`, `duration`, `pause`, `eof-reached`, `track-list`.
6. Save progress to `episode_progress` every N seconds (default 5 s) and on pause/quit.
7. When `time-pos / duration ≥ threshold`, mark the episode watched once and emit a WS event.
8. On `eof-reached` or quit near the end: emit `playback.ended` with the next episode ID.

### Backend — video selection & errors
9. Sort videos by the user's preferred quality/server, using the source's own `List<Video>.sort()` first.
10. Resolve lazy videos (`videoUrl` empty → `videoUrlParse`) right before launching.
11. On mpv HTTP 403/404 or an early exit: refetch `getVideoList` once (URLs often expire), then offer the next video in the list. Fall back to the backend proxy for headers mpv can't send.

### Frontend
12. "Play" on an episode → `GET videos` → `VideoPicker` (quality, server name, subtitle/audio counts). Remember the last choice per source.
13. Resume prompt when the saved progress is between 1 % and the threshold.
14. Episode list shows a progress bar and watched state; supports bulk "mark watched/unwatched".
15. Next-episode toast after `playback.ended` with a countdown (setting to auto-play).
16. Settings page section for player options.

### Stretch — embedded mpv
17. Prototype `--wid=<HWND>` with a child window created by Tauri, positioned over a placeholder `<div>`. Handle resize, fullscreen and focus. Keep it behind a setting.

### Tests
18. Unit tests for `MpvLauncher` argument building (header escaping, subtitle lists).
19. IPC client test against a fake named-pipe server.
20. Manual test matrix: MP4, HLS, MKV with ASS subs, external subs, multiple audio tracks, expiring URLs.

## 5. Checklist

### Launching
- [x] `MpvLauncher` builds args (headers, subs, audio, title, start, IPC pipe) (Stage 1: `MpvCommand`, with the source's values in a per-session include file; `MpvCommandTest`, and live with AnimeGG and KickAssAnime, §10)
- [x] mpv spawned + tracked + killed on shutdown (Stage 1: `PlaybackSessions`; `PlaybackApiTest`, and live: a closed app, a killed app, and a killed backend whose mpv the restarted one ends, §10)
- [x] `player/mpv.conf` + `input.conf` in place (Stage 1, with `player/IPC.md`; used live through `MADOYOMI_PLAYER_DIR`)

### IPC & progress
- [x] Named-pipe JSON IPC client with `observe_property` (Stage 2: `MpvIpcClient`, overlapped I/O on the pipe; `MpvIpcClientTest` on a real named pipe, and live with AnimeGG and KickAssAnime, §10)
- [x] Progress saved periodically and on quit (Stage 2: `PlaybackTracker` + `EpisodeProgress`, milliseconds in `EPISODE`; `PlaybackTrackerTest`, `EpisodeProgressTest`, `PlaybackApiTest`, and live: a quit halfway stored mpv's last position, §10)
- [x] Watched threshold marks the episode once (Stage 2: 85 %, once per session; `PlaybackTrackerTest`, `PlaybackApiTest`, and live with a seek to 99 %, §10)
- [x] `playback.*` WS events (Stage 2: the GraphQL subscription `playbackEvents` over the existing WebSocket: STARTED, PROGRESS, WATCHED, ENDED with the next episode, ERROR, CLOSED; `PlaybackApiTest`, and live, §10)

### Selection & errors
- [x] Preferred quality/server sorting (Stage 5: `VideoPreferences` after the source's sorts, stable, the server then the hoster's closest quality, with `sourceIndex` and `matchesPreference`; the picker's and auto-play's pick ranked remembered, preference, source; `VideoPreferencesTest`, `PlayerSettingsApiTest`, `videos.spec.ts`, `VideoPicker.spec.ts`, `AnimeDetailsView.spec.ts`, and live through the API and in `pnpm tauri dev` on AnimeGG and KickAssAnime, §10)
- [x] Lazy `videoUrlParse` resolution (Stage 1: `playEpisode` resolves the video right before the launch, through Phase 2's `resolveVideo`; `PlaybackApiTest`)
- [x] 403/expired retry + fallback to next video (Stage 4: `PlaybackSessions` fetches the videos again once and plays the same video from where it failed (RETRYING), else ERROR with the next video, which the app preselects in the reopened picker; `VideoChoicesTest`, `PlaybackApiTest`, `AnimeDetailsView.spec.ts`, and live through the API and in `pnpm tauri dev` with the header proxy's expiring mode, §10)
- [ ] Proxy fallback for headers mpv can't send (**deferred**, Stage 4: measured live, every header of AnimeGG (2) and KickAssAnime (7) reaches mpv's requests verbatim, so there is no header mpv can't send; Stage 6's matrix measured them again, on the MKV's and an HLS playlist's requests too; §9, §10)

### Frontend
- [x] `VideoPicker` dialog with remembered choice (Stage 3: the hosters with Load/Retry, quality and track counts, "Source's pick" and "Last used", the last choice per source on this device; `VideoPicker.spec.ts`, `videoChoices.spec.ts`, `videos.spec.ts`, and live in `pnpm tauri dev`: a non-default video played, and was preselected as "Last used" after an app restart, §10)
- [x] Resume / start-over prompt (Stage 3: the picker's footer between 1 % and 85 %; `progress.spec.ts`, `VideoPicker.spec.ts`, `AnimeDetailsView.spec.ts`, and live in `pnpm tauri dev`: "Resume from 12:37" after an app restart, and mpv started 0.00 s from the stored position, §10)
- [x] Progress + watched state in episode list; bulk mark (Stage 3: live rows from `playbackEvents` through `stores/playback.ts`, the Watched badge at once, checkboxes with Shift+click ranges and "Mark previous as watched", through `updateEpisodes`; `EntryItemList.spec.ts`, `realtime.spec.ts`, `AnimeDetailsView.spec.ts`, `EpisodeUpdateApiTest`, and live in `pnpm tauri dev`, §10)
- [x] Next-episode toast / auto-play setting (Stage 4: `NextEpisodeToast` with a 10 s countdown, `useNextEpisode`, "Auto-play the next episode" in Settings → Playback, on by default, on this device; `NextEpisodeToast.spec.ts`, `playerPrefs.spec.ts`, `playback.spec.ts`, `AnimeDetailsView.spec.ts`, `SettingsView.spec.ts`, and live in `pnpm tauri dev`, §10)
- [x] Player settings section (Stage 5: Settings → Player, the backend's `player-settings.json` through `playerSettings`/`setPlayerSettings`: the watched threshold, quality, server, subtitle and audio language (`TrackSelection` over IPC), the mpv path with `mpvCheck`, each used from the next play; auto-play stays on the device; `PlayerSettingsTest`, `TrackSelectionTest`, `PlayerLanguagesTest`, `MpvLocatorTest`, `PlaybackApiTest`, `SettingsView.spec.ts`, and live, §10)

### Tests
- [x] Launcher argument unit tests (Stage 1: `MpvCommandTest`, `MpvLocatorTest`)
- [x] IPC client test with fake pipe (Stage 2: `MpvIpcClientTest` against `FakeMpvServer`, a fake mpv on a real Windows named pipe, which `PlaybackApiTest` also runs as mpv)
- [x] Manual format matrix passed (Stage 6, live through the API on a fresh data dir: MP4 (AnimeGG), HLS (KickAssAnime), MKV with ASS subtitles and two audio tracks of its own (a generated file in place of a source's URL: none of the top 20 serves MKV), external subtitles (KickAssAnime's 8 WebVTT, AnimeOnsen's 9 ASS), external audio (KickAssAnime's 2), and expiring URLs at once and mid-play for MP4 and HLS; every row played, selected the expected tracks, saved its progress, and got its headers; §10)

### Stretch
- [ ] Embedded mpv via `--wid` prototype

## 6. Exit criteria

- [x] Watch an episode from the UI, close mpv halfway, reopen the app, and resume within ±5 s of where you stopped. (Stage 3 on the seeded `live-data`; **Stage 6 from a fresh data dir, twice**, in `pnpm tauri dev`: mpv's window closed halfway, stored 752966 ms, the app closed and started again, "Resume from 12:32", and mpv's first `time-pos` 752.966, 0.00 s off; after the fix round, on a second fresh data dir, stored 716500 ms and mpv at 716.500, 0.00 s off, §10.)
- [x] An episode passing the watched threshold shows as watched in the UI without a refresh. (Stage 3; **Stage 6 from both fresh data dirs**: a seek to 1290 s of 1475 s (87 %) showed the Watched badge on the playing row at once, and with the threshold changed to 70 % while episode 2 played, a seek to 72 % marked it in the same session, §10.)
- [x] The next episode starts from the end-of-episode prompt. (Stage 4 on the seeded `live-data`; **Stage 6 from both fresh data dirs**: episode 1 played to its end showed "Next: Episode 2 …" with its countdown, and episode 2 played 10.8 s (then 14.2 s) after the end with the remembered `[DUBBED] AnimeGG:360p`, without the picker; Cancel on the next toast played nothing, §10.)

## 7. Risks & notes

- Video URLs needing headers/cookies → 403 (PLAN.md §6, "Video URLs require headers/cookies"). Always pass `Video.headers`; the proxy is the fallback. Stage 4 measured every header arriving verbatim, so the proxy is deferred until a source needs one (§9).
- Many source URLs expire quickly: resolve them right before launch and retry once.
- Named pipe names must be unique per session to avoid clashes with a stale mpv.

## 8. Decisions closed here

- mpv in a separate window (MVP) vs. embedded: confirm the UX priority (PLAN.md §7). **Decided in Stage 1: a separate window for the MVP**; embedding (`--wid`) stays a stretch goal after the exit run (§9).
- Whether the backend or Tauri owns the mpv process (default: backend). **Decided in Stage 1: the backend** (§9).

### Stage plan (2026-11-07)

Agreed with the user in Stage 1. Each stage ends in something verifiable and a commit:
1. **Launcher and process** (steps 1–3, 10, 18; `player/`): `madoyomi.core.player`, with `MpvLocator`, `MpvCommand` (S4's header forms, subtitles, audio tracks, title, start, the IPC pipe, `--config-dir`, `--ytdl=no`, lib-16 `mpvArgs`) and `PlaybackSessions` (one session at a time, killed when the backend stops, orphans of a crashed backend ended at startup). Also `player/mpv.conf`, `input.conf` and `IPC.md`, the GraphQL `playEpisode`, `stopPlayback` and `playbackSession`, the shell passing `MADOYOMI_MPV`/`MADOYOMI_PLAYER_DIR`, and a plain **Play** on each episode row (the first video; Stage 3's picker replaces it). Verified by argument unit tests, a process test against a fake mpv, and AnimeGG and KickAssAnime live, through the API and in `pnpm tauri dev`.
   - *Done* (2026-11-07):
     - server: `MpvCommandTest`, `MpvLocatorTest` and `PlaybackApiTest` (a fake mpv: the command line and include file, replacing and stopping a session, mpv exiting by itself, a missing mpv, orphans);
     - app: Play's choice of video, its errors, and the busy row; the shell: the mpv and config-dir resolution;
     - live through the API (§10): AnimeGG's MP4 with its Referer from 60 s and KickAssAnime's HLS with 6 headers, 8 subtitles and 2 audio tracks, both advancing over the IPC pipe; a session replacing another; a killed backend's mpv ended by the next one; `PLAYER_NOT_FOUND`; no token or header on mpv's command line or in the logs;
     - live in `pnpm tauri dev`: Play on an AnimeGG and a KickAssAnime episode opened mpv with the episode's title; quitting mpv, closing the app and killing the app each left no mpv.
   - *Done* (2026-11-11):
     - server: `MpvIpcClient` (the named pipe with overlapped I/O, retries, replies, events, `observe_property`), `PlaybackTracker` (saves, the watched mark, progress events, how a session ended), `EpisodeProgress` (milliseconds in `EPISODE`, the next episode), the external tracks added over IPC with their labels, a graceful `quit`, and the subscription `playbackEvents`;
     - tests: `MpvIpcClientTest` (7) on a real named pipe served by `FakeMpvServer` (Java + JNA, test only), `PlaybackTrackerTest` (10), `EpisodeProgressTest` (3), and 3 new `PlaybackApiTest` cases with `FakeMpvServer` as mpv (progress, watched, ENDED with the next episode; a stop by `quit`; a failed load as ERROR);
     - app: `playbackEvents` refetches the anime's episodes on WATCHED and CLOSED, so the list's Watched badge follows; the session fields and the event type are in `types.ts`;
     - live through the API (§10): AnimeGG from 60 s, a save every 5 s, a seek to halfway and a quit stored mpv's last position to the millisecond; the same episode from halfway with a seek to 99 % was marked watched once, then ENDED with the next episode; KickAssAnime's 8 subtitle and 2 audio tracks carry their labels, and `stopPlayback` quit mpv with code 0.
2. **IPC and progress** (steps 4–8, 19): `MpvIpcClient` on the named pipe (`observe_property` on `time-pos`, `duration`, `pause`, `eof-reached`, `track-list`), a graceful `quit` before the kill, progress saved every 5 s and on pause and quit into `EPISODE.last_second_seen`/`total_seconds`, the episode marked watched once at the threshold, and a GraphQL subscription with progress, ended and error events. Verified by a fake-pipe test and a live check of the stored values after quitting halfway.
3. **Picker, resume and watched state in the UI** (steps 12–14): `VideoPicker` (hosters, quality, subtitle and audio counts, the last choice remembered per source), the "Resume from mm:ss / Start over" prompt, progress bars and watched marks that update live, and bulk mark watched/unwatched (`updateEpisodes`). Verified by component tests and live.
   - *Done* (2026-11-11):
     - server: `updateEpisode`/`updateEpisodes` (`EpisodeUpdateApiTest`, 6 tests): watched, bookmarked and the position; unwatched clears the position;
     - app: `VideoPicker` (hosters, Load/Retry, quality and track counts, the source's pick and the last-used choice, the resume prompt as its footer), the playback store with live rows ("Playing"/"Paused", "mm:ss left", a bar), the Watched badge at once, and bulk marks with Shift+click ranges and "Mark previous as watched";
     - live through the API: a non-default video, a resume after a backend restart (0.00 s off), WATCHED at 90 %, and the bulk marks;
     - live in `pnpm tauri dev` (§10): the picker with the DUBBED 360p played (height 360), a quit halfway shown as "11:57 left", "Last used" and "Resume from 12:37" after an app restart with mpv 0.00 s off, the row live while playing and paused, the Watched badge at 87 % without a refresh, and the three bulk marks. Both §6 criteria they cover are ticked. No fix was needed.
4. **Next episode and errors** (steps 8, 9, 11, 15): the next-episode toast with a countdown and auto-play; on a 403/404 or an early exit, the videos fetched again once, then the next video offered. The proxy fallback is decided by measurement (mpv sends every header itself, so it is likely deferred). Verified with an expiring URL from the header proxy (`spikes/mpv-play/header-proxy.mjs`) and live.
   - *Done* (2026-11-11):
     - server: `VideoChoices` (the same video found again after a refetch, the next video to offer) and `MpvLog` (the HTTP status from mpv's log, an early `eof`); `PlaybackSessions` retries a failed session once (RETRYING, the videos fetched again, the same video from where it failed), else ERROR with `retried`, `httpStatus`, `failedVideo` and `nextVideo`; `AnimeVideos.cachedHosters`;
     - tests: `VideoChoicesTest` (8) and 8 new `PlaybackApiTest` cases with `FakeMpvServer` as mpv, which now fails by its URL (refused at once, mid-play, an early `eof` after a 403, refused again after the refetch, the video gone after the refetch, a failure without an HTTP error, a stop during the retry);
     - app: `NextEpisodeToast` and `useNextEpisode` (a 10 s countdown, auto-play with the remembered choice and no picker, from the resume point; Cancel, another Play and leaving the page stop it), "Auto-play the next episode" in Settings → Playback (`stores/playerPrefs.ts`), "Retrying…" on the row, and the picker reopened with the error and the next video preselected;
     - the header proxy got an expiring mode (`/expire/<token>`), and the header measurement found nothing mpv can't send, so the proxy fallback is deferred;
     - live through the API: refused at once, then played from the asked 60 s (0.00 s off); expired mid-play (mpv's `eof` at 15:00 of 25:00 with a 403), then played on from 900 s; refused twice, then ERROR with `[SUBBED] AnimeGG:720p` as the next video;
     - live in `pnpm tauri dev` (§10): the toast's countdown played episode 4 with the remembered video, Cancel during the countdown and during the start played nothing (a fix: see §10), a refused URL was retried and played, and a double refusal reopened the picker with the next video, which played. The §6 criterion is ticked.
5. **Player settings** (steps 9, 16): the watched threshold, preferred quality, server and subtitle/audio language (sorting), and the mpv path override, in a Settings section. Verified by unit tests and a live change that takes effect.
   - *Done* (2026-11-12):
     - server: `PlayerSettings`/`PlayerSettingsStore` (`<data dir>/player-settings.json`), `VideoPreferences` (the order by the preferred server and quality, in `AnimeVideos`, with `sourceIndex` and `matchesPreference`), `PlayerLanguages` and `TrackSelection` (`sid`/`aid` set over IPC once the tracks load), the threshold read at every position, the mpv path first in `MpvLocator` with `mpvCheck`; GraphQL `playerSettings`, `setPlayerSettings`, `playerLanguages`, `mpvCheck`;
     - tests: 33 new (26 core, 7 api), among them a fake mpv with tracks of its own and per-type track ids;
     - app: Settings → **Player** (auto-play, "Mark as watched at", quality, server, subtitles, audio, mpv with Check), `usePlayerSettings`, the resume prompt and auto-play on the backend's threshold, the picker's **Preferred** mark and the ranking remembered → preference → source (the user's decision, §9); 13 new tests;
     - live through the API: the order for 720p and 2160p on AnimeGG and KickAssAnime, WATCHED at 62 % with 60 %, Spanish/English and off/Japanese on KickAssAnime's 8 subtitle and 2 audio tracks, and a wrong, a right and a cleared mpv path;
     - live in `pnpm tauri dev` (§10): 95 % made a 90 % episode offer "Resume from 22:07", 85 % didn't; 720p put `[SUBBED] AnimeGG:720p` first as Preferred while the remembered `[DUBBED] AnimeGG:360p` stayed preselected, and auto-play played it on the next episode; KickAssAnime preselected its 720p and mpv played Spanish subtitles and English audio; a wrong mpv path was `PLAYER_NOT_FOUND` naming the setting, the right one played, and clearing it went back to PATH. No fix was needed.
6. **Exit run**: the step 20 format matrix (MP4, HLS, MKV with ASS, external subtitles, multiple audio tracks, expiring URLs), every §6 criterion from a fresh data dir, and a fix round. The embedded `--wid` prototype (step 17) stays a stretch goal after the exit, not part of it.
   - *Done* (2026-11-12):
     - the baseline rerun (§10): 766 server tests green on the second build (upstream's `CefTest` flaked once), smoke 24/24, the app's 338 + 9, the live spec 9/9 with 42 documents, `cargo test` 17;
     - the format matrix, live through the API on a fresh data dir (§10's table): every row played, chose the expected tracks, saved its progress and got its headers. The MKV row is a generated MKV (H.264, two AAC tracks, two ASS tracks) in place of a source's URL, since none of the top 20 serves MKV; the mid-play HLS expiry is a generated HLS through the header proxy, since KickAssAnime's segment hosts refuse the proxy's client;
     - every §6 criterion from a fresh data dir in `pnpm tauri dev`, through the UI, twice (before and after the fix round), with the repo added, the extensions installed, the trust prompt, and the player settings from their defaults (a threshold change taking effect in the episode that played);
     - the fix round: an HLS stream that opens but never plays a frame (mpv reports its start position, then "no audio or video data played") is now fetched again once like any early failure (`PlaybackTracker.neverPlayed`; found live through the proxy). `ExtensionThreadsTest` counts the pool's threads by name. Tests: 2 new (768);
     - the header proxy rewrites HLS playlists so their segments expire with the token (`spikes/mpv-play/header-proxy.mjs`). Phase 4 is done; the `--wid` prototype stays open as the stretch goal.

## 9. Decision record

### Stage 1 (2026-11-07)

Decided by the user:
- **The stage plan above.**
- **mpv plays in its own window for the MVP.** mpv's own window brings its fullscreen, on-screen controller, keyboard handling and multi-monitor behaviour for free. Embedding with `--wid` has focus, resize and fullscreen problems with WebView2, and the exit criteria don't need it. It stays a stretch goal (step 17), behind a setting. `--wid` takes an HWND from any process, so this doesn't tie down who owns mpv.
- **The backend owns mpv.**
  - The `Video` objects stay in the JVM, and some sources serve their video through local servers inside the JVM (missav, onetwothreeanime, reanime; Phase 2 §10).
  - The backend resolves the video right before the launch, can fetch it again after a 403 (Stage 4), and writes progress to the database itself (Stage 2).
  - Events go out through the existing GraphQL subscriptions, and Kotlin tests cover the whole path.
  - mpv is the JVM's child, so it is in the shell's Job Object too.
- **Where mpv comes from.**
  1. the player settings' override (Stage 5);
  2. `MADOYOMI_MPV`;
  3. `mpv.exe` on PATH.

  The shell sets `MADOYOMI_MPV` to `tools/bin/mpv/mpv.exe` in dev and to the bundled `resources/mpv/mpv.exe` in release (Phase 7), when that file is there. Otherwise it sets nothing, and in dev the backend finds the installed `C:\Program Files\MPV Player\mpv.exe` on PATH. mpv's config dir is `MADOYOMI_PLAYER_DIR` (the shell sets it to the repo's `player/` in dev and to `resources/player` in release), else `<data dir>/player`. A missing mpv is `PLAYER_NOT_FOUND` (503), and a failed start is `PLAYER_FAILED` (500), both new error codes.

Decided in the stage (defaults, not asked):
- **The source's values go into a per-session config file, not onto the command line.** These are the headers, the title, the subtitle and audio URLs and the source's `mpvArgs`. The file is `<data dir>/cache/player/<session>.conf`, read with `--include`, one option per line in mpv's fixed-length quoting (`name=%<UTF-8 bytes>%value`), and deleted when mpv exits. The command line has only Madoyomi's values: the config dir, the include file, the pipe, `--ytdl=no`, `--idle=no`, `--force-window=immediate`, `--msg-level=all=warn`, the start time, and the URL after `--`. The reasons:
  - Java's `ProcessBuilder` on Windows doesn't escape a `"` inside an argument, so `sec-ch-ua: "Chromium";v="130"` would be cut, and a crafted header could add mpv options;
  - other processes can read a command line, and headers may carry cookies.
- **Header forms are S4's:** `User-Agent` becomes `user-agent`, `Referer` becomes `referrer`, and every other header is one `http-header-fields-append` (step 1 is corrected). A header with an invalid name or a line break is left out and logged, and so is a track without a URL.
- **A source's `mpvArgs` can't take control of mpv.** The IPC options, config and script loading (`config*`, `include`, `profile`, `script*`, `load-scripts`, `input-conf`/`-file`/`-commands`), `ytdl*`, written files (`log-file`, encoding outputs, `stream-record`, `record-file`, `screenshot-*`, `watch-later-*`, `save-position-on-quit`) and `idle`, `playlist`, `start`, `force-media-title` are left out, with any list suffix, and logged. Every other option is applied, as the Aniyomi app does.
- **One session at a time.** `playEpisode` stops the running mpv first, and `stopPlayback` stops it alone. Until Stage 2's IPC `quit`, stopping is `Process.destroy()` (TerminateProcess on Windows). The pipe is `\\.\pipe\madoyomi-mpv-<UUID>`, so a name is never reused.
- **mpv's lifetime.**
  - When the backend stops normally (stdin closed, the app closed), a shutdown hook stops mpv and deletes the session's files.
  - When the app dies, the Job Object takes mpv down too.
  - When the backend crashes and the shell restarts it, the new backend ends the old mpv. Every session writes `<data dir>/cache/player/<session>.session` (pid, executable, start time), and at startup `reapOrphans` ends a live process only if all three match, so a reused pid is never hit. Then it clears the directory.
- **mpv gets the backend's environment without `MADOYOMI_TOKEN`.** mpv's output goes to `<data dir>/logs/mpv.log`, the last session's only.
- **The API:**
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})` → `{session}` resolves the video of the episode's last `fetchEpisodeVideos` (through Phase 2's `resolveVideo`, which fetches again if needed) and launches mpv;
  - `stopPlayback(input: {})` → `{session}` (null if nothing was playing);
  - `playbackSession` gives the running session, else the last one;
  - `PlaybackSessionType` is `id`, `episodeId`, `animeId`, `title` ("<anime> - <episode>"), `videoTitle`, `state` (`RUNNING`, `EXITED`, `STOPPED`), `startedAt`, `endedAt` and `exitCode`.

  These are GraphQL mutations and a query rather than the REST paths of §3, to match the rest of the API. The UI never sees a video's URL or headers: the backend keeps them.
- **Progress will live in the existing `EPISODE.watched`, `last_second_seen` and `total_seconds`** (M0070; GraphQL already has them), Aniyomi's model, rather than a new `episode_progress` table. So Stage 2 needs no migration.
- **mpv's config** (`player/mpv.conf`): no watch-later files (Madoyomi keeps the position), `keep-open=no` (so mpv ends at the end of the file, for Stage 4's next-episode flow), a larger demuxer cache, `hwdec=auto-safe`. `input.conf` makes `Shift+Q` quit without writing a watch-later file. `player/.gitignore` keeps anything mpv might write there out of git.
- **Play in the UI until the picker:** each episode row gets a Play button that takes the first READY hoster (in the source's order) and its `preferred` video, else its first. The row says "Starting…" until mpv is up. A failure shows above the rows: the failed hoster's error, `PLAYER_NOT_FOUND` with a hint, or "no video".

### Stage 2 (2026-11-11)

Decided in the stage (defaults, not asked):
- **Progress is stored in milliseconds**, in the existing `EPISODE.last_second_seen` and `total_seconds`, despite their names. The Aniyomi app's player writes `position * 1000` and `duration * 1000` into these fields (`PlayerViewModel.onSecondReached`), so backups and Phase 6's history agree with Aniyomi's. The GraphQL fields keep Aniyomi's names (`lastSecondSeen`, `totalSeconds`), now described as milliseconds. No migration.
- **The IPC client is plain JDK:** the named pipe is opened as an `AsynchronousFileChannel`, so with overlapped I/O, and a reader thread waits on reads while other threads write. A synchronous handle (`RandomAccessFile`) would serialize them: a pending read would block every command. Elsewhere it is a Unix socket (`SocketChannel`). JNA is a test dependency only: the fake mpv has to *serve* a named pipe, which Java can't do.
- **What is saved, and when:**
  - every 5 s of playback, starting with the first position;
  - at once on pause, after a seek (the first position once playback restarts), at the threshold and at `end-file`;
  - when the session ends, with the last position mpv reported.

  A save with the same values is skipped, and a duration of 0 never replaces a known one. Nothing is written from the backend's shutdown hook, because the database may be closing too: the last save is at most 5 s old.
- **Watched:** at 85 % of the duration (`PlaybackSessions.watchedThreshold`, Stage 5's setting), tried once per session. `watched` is set only if it isn't already, so an episode the user unmarks while it plays isn't marked again by that session. A video without a duration (live) is never marked. The position is kept when an episode is marked (Aniyomi's "preserve watch position" default).
- **Ended:** at the end of the file, or when the user quits past the threshold (Phase 4 step 8's "quit near the end": the credits). The next episode is the following one in the source's order (`source_order` + 1 and up, the same anime). A stop by Madoyomi (`stopPlayback`, a new `playEpisode`) is never ENDED or ERROR.
- **Error:** mpv's `end-file` with `reason: error` ("mpv could not play the video: <file_error>"), or a non-zero exit code that wasn't ours.
- **Events:** the GraphQL subscription `playbackEvents(episodeId)`: STARTED (the pipe connected), PROGRESS (at most once a second, and at once on pause, resume and seek), WATCHED, ENDED (`nextEpisodeId`, `reason`), ERROR (`message`), CLOSED (always last). Each carries the session, which now has `positionSeconds`, `durationSeconds` and `paused` (also in `playbackSession`). Nothing is replayed.
- **Stopping:** `quit` over IPC first, then wait up to 3 s for mpv to exit (code 0). Only then, or when the pipe never connected, is the process destroyed (TerminateProcess, code 1). The same applies to the backend's shutdown hook (2 s).
- **External tracks over IPC:** subtitle and audio tracks are added with `sub-add`/`audio-add` (async), with the source's label as their title, once the file is open (`file-loaded`, or a non-empty `track-list` if the pipe connected late), instead of `sub-files-append`/`audio-files-append` in the include file. Those can't name a track, so mpv showed the URL's file name. The first subtitle track is selected (as mpv selects a single `--sub-file`), and the first audio track only when the file has no audio of its own (found live: KickAssAnime's HLS has none, and mpv's selection has already run when the tracks arrive, so it played silent). Stage 5's language preference will choose between them. The trade-off: without the pipe, there are no external tracks, but there is no progress either.
- **The pipe:** the backend retries every 100 ms for up to 60 s after the start, and stops when mpv exits. A session without a pipe plays on, but nothing is saved (logged).
- **UI, only what this stage needs:** the app subscribes to `playbackEvents` and fetches the anime's episodes again on WATCHED and CLOSED, so the episode list's watched state (`isWatched`, already shown) and the stored position follow without a manual refresh. Progress bars, the resume prompt and live progress are Stage 3.

### Stage 3 (2026-11-11)

Decided in the stage (the prompt left these to the stage; defaults, not asked):
- **Play always opens the video picker**, even when a choice is remembered. That choice (else the source's pick) is preselected and its button focused, so Enter plays it: one extra key press over playing at once. The reasons:
  - the source is asked for the videos each time anyway (URLs expire), which takes 10–20 s with AnimeGG, and the dialog shows that it is fetching;
  - an episode's hosters and qualities differ from the last one's, and a lazy or failed hoster has to be seen;
  - the resume prompt sits in the same dialog.

  Stage 4's auto-play of the next episode may play the remembered choice without the dialog.
- **The picker** (`VideoPicker`) lists each hoster in the source's order with its state:
  - a READY hoster shows its videos;
  - a lazy one is "Not loaded", with Load;
  - a failed one shows its error code and message, with Retry (both through `fetchHosterVideos`).

  A video shows its title, the resolution when the title doesn't have it, and its subtitle and audio-track counts. "Source's pick" marks the first READY hoster's `preferred` video, and "Last used" marks the remembered one. The dialog stays open, "Starting…", until `playEpisode` returns, and shows a failure there (`PLAYER_NOT_FOUND`, …).
- **The last choice is remembered per source, on this device** (`localStorage` `madoyomi.videoChoices`), like the other UI preferences (Phase 3 §9). What is kept is the hoster's name and the video's title and resolution, since indexes change between episodes. It is saved when mpv starts, not when a radio button is clicked.
  - Matching: the hoster by name (READY), then the video by its exact title, else by resolution, else that hoster's preferred or first video.
  - A remembered hoster that is lazy is loaded by itself. If it is missing or failed, the source's pick is preselected.
- **The resume prompt is the picker's footer.** "Resume from mm:ss" (the default) and "Start over" appear when the episode isn't watched, its duration is known, and the position is at least 1 % and below the watched threshold (85 %, `WATCHED_THRESHOLD` until Stage 5's setting). `startSeconds` is `lastSecondSeen / 1000`. Otherwise there is only Play, from the start:
  - a watched episode starts over without asking (Aniyomi's behaviour);
  - so does one below 1 %, or one with no known duration.

  While mpv plays the episode, its live position counts rather than the stored one, which is up to 5 s older.
- **Live progress goes through a Pinia store, not the query cache.** Every `playbackEvents` event goes into `stores/playback.ts`, and the episode list draws the playing row from it: "Playing"/"Paused", "mm:ss left", and a bar. So a PROGRESS event (once a second) refetches nothing.
  - WATCHED marks the episode in the cached list at once, so the badge appears without waiting for a refetch.
  - CLOSED writes the session's last position into the cached list and clears the store. Both then refetch the episodes, as Stage 2 did.
  - After a WebSocket reconnect, the session the store followed is forgotten (its CLOSED may be lost), and its episodes are refetched.
- **A row's progress** shows when it has a position and isn't watched (or is playing again): "12:28 left" with a bar, or "Stopped at 1:01" without a duration.
- **Bulk marks.**
  - Selection: each episode row has a checkbox, and Shift+click selects or clears the range from the last row clicked, in the order shown. The bar above the rows has the count, Mark watched, Mark unwatched, "Select all shown" and Clear. The selection is cleared when the entry changes and after a mark; it stays after a failed one, for a retry.
  - **Mark previous as watched** appears with exactly one row selected. It marks every earlier unwatched episode in the source's order (lower `source_order`, all episodes, not only those shown), the same order as Stage 2's next episode. Mihon goes by the list's current sort instead; the source's order doesn't change with the user's sort.
  - Chapters aren't selectable yet (Phase 5).
- **`updateEpisode` / `updateEpisodes`** (api, `AnimeMutation`, in the shape of upstream's `updateChapter(s)`). The patch is `isWatched`, `isBookmarked` and `lastSecondSeen` (milliseconds, a `LongString`). Episodes are returned in the given order, each once, and an unknown id is `NOT_FOUND` with nothing written.
- **Unmarking clears the position.** `isWatched: false` sets `last_second_seen` to 0, as the Aniyomi app's `SetSeenStatus` does, so an unwatched episode starts from the beginning without a resume prompt. A `lastSecondSeen` in the same patch wins, clamped to 0 and to the known duration. Marking watched keeps the position (Stage 2's rule), and the duration is never changed. A session playing the episode keeps saving its position and doesn't mark it again (Stage 2's once-per-session rule).

### Stage 4 (2026-11-11)

Decided in the stage (defaults, not asked):
- **The next-episode toast** appears on the details page of the anime that played, when a session ENDS (the end of the file, or a quit past 85 %) with a next episode in the source's order: "Next: <episode>", a countdown, **Play now** and **Cancel**. It is drawn by the episode list (`useNextEpisode`), so it is only there while that page is open.
- **Auto-play is on by default**, a device preference like the others (Phase 3 §9): `localStorage` `madoyomi.autoPlayNext`, switched in Settings → Playback ("Auto-play the next episode"). Stage 5 moves it into the player settings section. Off, the toast only offers the episode ("Auto-play is off"), without a countdown.
- **The countdown is 10 s** (`NEXT_EPISODE_COUNTDOWN_SECONDS`): enough to read the toast and reach Cancel, short enough to keep watching. mpv's window closes at the end of the file (`keep-open=no`), so the toast is in view.
- **Auto-play skips the picker** (Stage 3's note): it fetches the episode's videos, takes the remembered choice for the source (a lazy remembered hoster is loaded first), else the source's pick, and plays. That is what Enter in the picker would do. The choice is remembered again, as any play.
- **It starts from the episode's resume point, by the picker's own rule:** between 1 % and 85 %, from the stored position; otherwise (a watched episode, one below 1 % or without a duration) from the start. So an episode partly watched before continues, as "Resume" is the picker's default. A watched one starts over, as in Aniyomi.
- **When auto-play can't play** (no video, the source fails, `PLAYER_NOT_FOUND`…), the picker opens for that episode with the error, so the user can choose.
- **What stops it:** Cancel; another Play (a new session that isn't the one it started); opening the picker; leaving the page. Cancel and leaving the page also stop a start already on its way: a start still fetching the videos plays nothing, and one whose `playEpisode` was already sent is stopped with `stopPlayback` once it returns (found live, §10). Opening the picker only dismisses the toast, since the user is about to play something.
- **When a session is retried** (`PlaybackSessions`, contract in player/IPC.md "When the video doesn't play"): ERROR's conditions (mpv's `end-file` with `reason: error`, or a non-zero exit that wasn't Madoyomi's), and then either:
  - **early**: mpv never reported a position (`time-pos`) in that session, so it never got the video playing (refused at once, a crash at start, an exit before the pipe opened); or
  - **an expired URL**: mpv's log has an HTTP 401, 403, 404 or 410.

  A failure after playback started without such a status (a decoding error, a crash) is ERROR at once, without a refetch.
- **The HTTP status is read from mpv's log**, both are used: the `end-file` event says that it failed (`reason: error`, `file_error: "loading failed"`, no status), and the last `HTTP error NNN` that FFmpeg wrote to `<data dir>/logs/mpv.log` (the session's own output, at `--msg-level=all=warn`) says why. The status goes into the message ("… (HTTP 403)") and the event's `httpStatus`.
- **An `eof` that isn't the end:** a URL that expires while it plays makes mpv log the 403 and end the file as `eof` with exit code 0 (found live). An `eof` more than 10 s before a known duration, with an HTTP error in the log, is treated as a failure ("mpv stopped reading the video at 15:00 of 25:00"), not as ENDED. Without an HTTP error it stays the end of the file.
- **The retry:** once per play (a retried session that fails is final). The backend sends RETRYING, fetches the episode's videos again in the background, finds **the same video** (the hoster by name, then the video by title, else by resolution; nothing else stands in), resolves it and plays it from the failed session's last position, else where it started. The failed session's CLOSED follows. A `playEpisode` or `stopPlayback` meanwhile cancels the retry. The row says "Retrying…" from RETRYING to that CLOSED.
- **The fallback:** when it isn't retried or the retry fails (the source fails, the video is gone, mpv can't start, or it fails again), ERROR carries `retried`, `httpStatus`, `failedVideo` and `nextVideo`: the next video in the source's order after the failed one (the READY hosters in order, each one's videos in order, wrapping around; never the failed one; none when there is no other). The app reopens the picker for that episode with the error and that video preselected ("… is selected instead"). The remembered choice changes only if the user plays it.
- **The proxy fallback is deferred** (step 11, PLAN.md §6 "Video URLs require headers/cookies"). Measured through the header proxy, every header AnimeGG (2) and KickAssAnime (7) send reached mpv's request verbatim, values with commas and semicolons included, and KickAssAnime's `accept` replaced mpv's own. The include file (Stage 1) sends each header on its own line, so the only headers mpv can't send are those Stage 1 leaves out (an invalid name or a line break), which no measured source has. Revisit if a source needs one, or for cookies set during playback.
- **Test-only ways in:** `FakeMpvServer` (a test file) fails as its URL says (`fakempv-error`, `fakempv-fail-at`, `fakempv-eof-at`, `fakempv-status`), so a scripted fake source hands it a URL that fails on the first fetch only. Live, a scratch wrapper set as `MADOYOMI_MPV` (gitignored, `tools/dev-data/agent/mpvwrap`) sent a launch's URL through the header proxy's new expiring mode. Nothing in the release code path knows about either.

### Stage 5 (2026-11-12)

Decided by the user:
- **The remembered choice comes first.** What the picker preselects, and what auto-play plays: the last choice for the source (Stage 3), else the player settings' preferred server and quality, else the source's pick. The preference orders the list either way, and decides for a source not played yet, or when the remembered hoster isn't in the episode (a remembered hoster whose video is gone gives that hoster's preference video). The reason: a per-source choice such as a dub (`[DUBBED] AnimeGG:360p`) says more than a global quality.

Decided in the stage (defaults, not asked):
- **The player settings are the backend's**, kept in `<data dir>/player-settings.json` (Madoyomi's own file; upstream's `server.conf` would mean editing upstream's `ServerConfig` and its generated code), and reached through GraphQL: `playerSettings`, `setPlayerSettings(input: {patch})`, `playerLanguages`, `mpvCheck(path)`. The reasons:
  - the backend is what applies them: the watched mark (the tracker), which mpv starts (`MpvLocator`), the tracks it selects over IPC once they are loaded, and the order of the videos, which the indexes of `playEpisode` and Stage 4's "next video" use;
  - they go with the data dir, as the library does: a fresh data dir starts from the defaults, and the app and a script on the same data dir agree;
  - a device preference sent with each play wouldn't reach the retry, which runs in the backend.

  A file that can't be read gives the defaults (logged), a value out of range in it its default, and an unknown key is ignored. A change is written to a temporary file and moved over the old one.
- **Auto-play stays a device preference** (`localStorage` `madoyomi.autoPlayNext`): only the UI acts on it. It moved into the Player section, which says it is kept on this device.
- **The watched threshold is 50 % to 95 %, in steps of 5** (default 85 %; a value between steps is rounded to one, one outside is `BAD_REQUEST`). 100 % isn't offered: mpv's last `time-pos` stops short of the duration (1499.958 s of 1500.125 s in Stage 2), so the mark would never come. The tracker reads it at every position, so a change applies to the session that plays, and to "a quit past the threshold is ENDED". The app's resume prompt and auto-play's resume point use the backend's value (85 % until it is known).
- **Sorting (step 9)**, after the source's `sortHosters()`/`sortVideos()`, both stable (`VideoPreferences`):
  - hosters whose name contains the preferred server (ignoring case) go first;
  - in each hoster, first the preferred server's videos (the hoster's name or the video's title contains it: a source without hosters, such as AnimeGG, puts the server into the title), then, within each of those, the videos at the hoster's *target* quality: the preferred one if the hoster has it, else the highest below it, else the lowest above it, or its highest for "Highest". A video's quality is its `resolution`, else the last "<digits>p" in its title;
  - the settings are read when the fetch starts, and a lazy hoster loaded later in the same fetch is sorted by them too. `index` is the position in the sorted list (what `playEpisode`, `fetchHosterVideos` and `resolveEpisodeVideo` take), and the new `sourceIndex` is the source's own position, on hosters and videos;
  - the new `matchesPreference` marks the videos that are of the preferred server or at the target quality (false without a preference). The preference's pick is the first such video in the list's order, marked **Preferred** in the picker. "Source's pick" still means the source's own: its first READY hoster by `sourceIndex`, and that hoster's `preferred` video, else its first by `sourceIndex`;
  - Stage 4's `VideoChoices.next` (the video offered after a failure) follows the sorted order, so it is the next best by the preference.
- **Track languages.** The settings name a language from a built-in table of 21 (`PlayerLanguages`: English and native names, ISO 639-1 and 639-2 codes). A track's label or `lang` matches when one of its words is a name or a three-letter code ("English (eng)", "English [CC]", "Português (Brasil)", "jpn"), or the whole label is a code with an optional region ("en", "en-US", "pt_BR"). A two-letter code inside a longer label doesn't count, so "it" in a title isn't Italian.
- **Which tracks play** (`TrackSelection`): the external tracks are now all added with `auto`. Once they have loaded (or failed, at most 30 s), the session reads `track-list` and sets `sid` and `aid`:
  - a language selects the first matching track, the file's own tracks before the source's (in the source's order), a forced (signs-only) track only when nothing else matches;
  - subtitles "Off" sets `sid=no`;
  - no language, or nothing matching, keeps Stage 2's rule: the source's first subtitle track, else mpv's own choice; the file's own audio, else the source's first audio track.

  This also runs for a file with tracks of its own and no external ones (an MKV) when a language is set. The choice is logged ("subtitles track 2, audio track 3").
- **The mpv path** comes first in `MpvLocator` (then `MADOYOMI_MPV`, then PATH). Surrounding quotes are taken off (Explorer's "Copy as path" adds them). A path that isn't a file is `PLAYER_NOT_FOUND`, "mpv was not found at … (set in Settings → Player)", with `details.origin` `SETTINGS`. A wrong path can be saved, since the user may fix the file rather than the setting; the check shows it. `mpvCheck(path)` checks a typed path, and without one the mpv the backend would start now: the file is there, and `<it> --version` exits with 0 within 10 s and prints a line starting with "mpv ". Its first line is shown with where the mpv comes from.
- **The Settings section:** "Playback" became **Player**. It holds auto-play, "Mark as watched at", the preferred quality, the preferred server, subtitles ("The source's first", "Off", a language), audio ("The source's choice", a language) and mpv (a path field with Check). A select saves as it changes; a text field saves when it loses focus or on Enter, trimmed. The value shows at once, and a refused change goes back with the error under the section. Check runs on what is typed without saving it, and after a save the mpv in use is checked again. The `PLAYER_NOT_FOUND` hint now points to Settings → Player.

### Stage 6 (2026-11-12)

Decided in the stage (defaults, not asked):
- **A stream that never played a frame is an early failure** (Stage 4's retry), even when mpv reported a position. mpv reports `time-pos` (the start position) as soon as the file is open, so an HLS stream whose playlist opens but whose segments all fail had a position, ended with mpv's own `file_error` "no audio or video data played", and was ERROR at once without a refetch (found live, §10). `PlaybackTracker.neverPlayed` is now "no position, or that `file_error`", and `PlaybackSessions` retries on it. A failure after frames played still needs an HTTP status to be retried.
- **`ExtensionThreadsTest` counts the source pool's threads by name** (`anime-source-<n>`, never reused), not by `poolSize`: idle threads that earlier tests left end after 60 s, so the size could drop while the extension's code still created its three threads (it failed in 3 of Stage 5's 7 builds). The leak check after it is unchanged. Upstream's `CefTest` (a live JBR download that picks up `SocksProxyTest`'s host) is left as it is: it is upstream's, and a rerun passes.
- **The MKV row of the matrix uses a generated file.** None of the top 20 yuzono extensions serves MKV (their video hosts give HLS, MP4 or DASH; docs/research/anime-compat.md), so a scratch wrapper (`MADOYOMI_MPV`) replaced one AnimeGG video's URL with a 10-minute MKV made with FFmpeg (H.264, AAC Japanese and English, ASS English and Spanish) on a local server. The backend, the include file with AnimeGG's headers, the IPC, the track choice and the progress are the real ones.
- **Phase 4 is closed** with the `--wid` prototype open (the stretch goal, after the exit) and the proxy fallback deferred (no header mpv can't send was found, MKV and HLS included).

## 10. Findings log

### Stage 1 (2026-11-07)

- **Baseline before the stage** (feat/phase-3-exit merged):
  - server: `.\gradlew.bat build` green with 669 tests (core 606 with 9 skipped, AndroidCompat 7, ext-host 8, api 48);
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: `pnpm test` 33 files, 256 passed + 9 skipped; `pnpm lint`, `pnpm typecheck` and `pnpm format:check` clean;
  - the live spec: 9/9 against a seeded data dir;
  - `cargo test`: 15; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

  Core's skip count varies between runs: 9 in this baseline, 8 in the final build (the prompt had 8). No test was removed.
- **After the stage:**
  - server: 688 tests, 0 failures (core 620 with 8 skipped, AndroidCompat 7, ext-host 8, api 53). That is 19 new ones: `MpvCommandTest` 8, `MpvLocatorTest` 6, `PlaybackApiTest` 5;
  - smoke: 24/24 on the new jar;
  - app: 34 files, 266 passed + 9 skipped. The new ones are `videos.spec.ts` (6), two Play tests in `AnimeDetailsView.spec.ts`, and the readable lines for the two new error codes;
  - the live spec: 9/9 on the new jar, with 35 documents validated (31 before; the four playback ones are new);
  - `cargo test`: 17 (the mpv and config-dir resolution).
- **Live through the API** (a scratch script driving the real jar and the installed mpv, with `MADOYOMI_PLAYER_DIR=player/`; mpv's properties were read over the named pipe):

  | Case | Result |
  |---|---|
  | AnimeGG, One Piece ep. 1, `[SUBBED] AnimeGG:1080p` (MP4; the host answers 500 without the Referer) from 60 s | `time-pos` 60 → 63.1; `referrer` = `https://www.animegg.org`, the User-Agent set; `media-title` = the anime and episode; `ytdl` off; duration 1500 s |
  | KickAssAnime, Bungou Stray Dogs 5th Season ep. 1, VidStreaming 1080p (HLS; 7 headers, 8 subtitles, 2 audio tracks) | `time-pos` 0 → 3.0; `http-header-fields` has 6 entries (plus the User-Agent); `track-list/count` = 11 (video + 8 subtitles + 2 audio). This is the multi-audio source Phase 0 S4 had no example of: `--audio-files-append` works |
  | A second `playEpisode` while one plays | the first mpv ended; the second started at 120 s |
  | `stopPlayback` | `STOPPED`; mpv gone; the session's files deleted |
  | The backend killed while mpv plays | mpv kept playing; the next backend ended it at startup ("ended the mpv … an earlier backend left running") |
  | `MADOYOMI_MPV=C:\nowhere\mpv.exe` | `PLAYER_NOT_FOUND`, with `details.path` and `details.origin` |
  | mpv's command line (`Win32_Process`) | only the config dir, include file, pipe, flags, start time and URL: no header and no token |
  | `token=` in `madoyomi.log`, `sidecar.log`, `mpv.log` | none |

- **Live in `pnpm tauri dev`** (driven by clicks with computer use, on the seeded data dir):
  - `sidecar.log` shows `player: mpv from PATH, config D:\Projects\madoyomi\player`;
  - Play on One Piece ep. 1179 (AnimeGG): the row said "Starting…", then mpv opened its own window, titled "One Piece - Episode 1179 - mpv", and played. Quitting it with `q` logged "mpv exited with code 0";
  - Play on "Oshi no Ko" ep. 11 (KickAssAnime, HLS) played in mpv;
  - closing the app window with mpv playing: the backend shut down and mpv was gone. In the first build, the shutdown hook left that session's two files in `cache/player` (the next start would have cleared them). The hook now deletes them too. That was rechecked on the final jar by closing a playing backend's stdin, as the app does: the backend exited with code 0, and neither mpv nor a file was left. It wasn't rechecked in `tauri dev`;
  - killing `madoyomi.exe` with mpv playing: the Job Object took the JVM and mpv down.
- **AnimeGG's Play takes 10–20 s** before mpv opens: the episode's videos are fetched, then resolved, then mpv connects. Stage 3's picker shows the videos first, and Stage 4 adds a timeout.
- **mpv's log was empty** at `--msg-level=all=warn` for these streams. mpv writes no file into `player/` (checked after the runs), and `player/.gitignore` guards it anyway.
- **Tooling for this session.** The agent's shell was a Linux VM without Windows tools, so the builds, tests and live checks ran through a small job runner the user started (`tools/dev-data/agent/`, gitignored), and the app was driven with computer use. Nothing of it is in the repo.
- **Not checked:**
  - the header proxy (`spikes/mpv-play/header-proxy.mjs`): AnimeGG's real host refuses a request without its Referer, which covers the same thing; Stage 4 uses the proxy for expiring URLs;
  - a release build's paths (`resources/mpv`, `resources/player`): only the shell's unit test covers them (Phase 7);
  - a subtitle track's language label in mpv: external tracks load by URL, and mpv shows their file names. Stage 2 can name them over IPC (`sub-add` with a title).

### Stage 2 (2026-11-11)

- **Baseline before the stage** (feat/phase-4-launcher merged):
  - server: `.\gradlew.bat build` green with 688 tests, 0 failures (core 620 with 8 skipped, AndroidCompat 7, ext-host 8, api 53);
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean;
  - the live spec on the baseline jar: 8/9. The schema check failed only on this stage's new fields (`playbackEvents`, `positionSeconds`, `durationSeconds`, `paused`), because the app's documents were already edited when it ran. The baseline's own 35 documents validated;
  - app: Stage 1's record (34 files, 266 passed + 9 skipped), not rerun before the edits. The first run on this branch had 266 passing plus 1 failing (a subscription count this stage changed), so no earlier test was lost.
- **After the stage:**
  - server: 711 tests, 0 failures (core 640 with 8 skipped, AndroidCompat 7, ext-host 8, api 56). That is 23 new ones: `MpvIpcClientTest` 7, `PlaybackTrackerTest` 10, `EpisodeProgressTest` 3, `PlaybackApiTest` 3. `MpvCommandTest`'s track test was rewritten for the tracks over IPC;
  - smoke: 24/24 on the new jar;
  - app: 34 files, 267 passed + 9 skipped (the new realtime test); `pnpm lint`, `pnpm typecheck` and `pnpm format:check` clean;
  - the live spec: 9/9 on the new jar, with 36 documents validated (35 before; `PLAYBACK_EVENTS_SUBSCRIPTION` is new);
  - `cargo test`: 17 (no Rust change).
- **The overlapped pipe works as expected.** `AsynchronousFileChannel.open(Path.of("\\\\.\\pipe\\…"), READ, WRITE)` opens mpv's pipe. Reads and writes run at once from different threads, which `MpvIpcClientTest` checks with 100 commands from 4 threads while events stream. A broken pipe fails the pending read with an `IOException`, which is how the client sees mpv exit.
- **Live through the API** (a scratch script driving the real jar and the installed mpv on the seeded data dir; mpv muted over a second IPC client). AnimeGG, One Piece ep. 2, `[SUBBED] AnimeGG:1080p` (MP4, duration 1500.125 s):

  | Step | Stored (`episodes` query) |
  |---|---|
  | before | `isWatched` false, `lastSecondSeen` 0, `totalSeconds` 0 |
  | played from 60 s, read at ~72 s | `lastSecondSeen` 70070, `totalSeconds` 1500125 (the 5 s save at 70 s) |
  | a seek to 750 s, then `quit` over IPC at mpv's `time-pos` 752.043710 | `lastSecondSeen` 752043, `totalSeconds` 1500125, `isWatched` false |
  | played again from 750 s, a seek to 1488 s (99 %), played to the end | `isWatched` true, `lastSecondSeen` 1499958 |

  The events, in order (PROGRESS runs collapsed):
  - the quit halfway: STARTED → PROGRESS ×17 (60.0 … 752.0) → CLOSED (EXITED, exit 0, position 752.04);
  - the seek to the end: STARTED → PROGRESS ×4 (750.0 … 752.0) → WATCHED (1488) → PROGRESS ×12 (1488.1 … 1499.2) → ENDED (`next=3`, `reason=eof`) → CLOSED (EXITED, exit 0).

  The first run of the same script, on episode 1, gave the same values and `next=2`. The log has "episode 2 marked watched at 1488.0 s" and "ended (eof), next episode 3".
- **KickAssAnime** (Bungou Stray Dogs 5th Season ep. 1, VidStreaming HLS with 8 subtitle and 2 audio tracks): every track shows its label as its title in mpv ("English (eng)", "Japanese", …). The source's first subtitle (English) is selected. mpv numbers the tracks in the order they finish loading (async), so English came out as track 8 rather than 1. `stopPlayback` quit mpv over IPC: STOPPED, exit code 0, 6.09 s stored as 6089 ms, `totalSeconds` 1420045.
- **Found live and fixed:** the HLS stream has no audio of its own. With `audio-add … auto` arriving after mpv's track selection, no audio track was selected, so the video played silent (hidden by the mute). Stage 1's `--audio-files-append` was selected at load. Now the session checks `track-list` first and selects the first external audio track when there is no other: `aid=1` (Japanese, the source's first) in the rerun.
- **No token** in `madoyomi.log`, `sidecar.log`, `sidecar.1.log` or `mpv.log` after the runs. mpv wrote nothing into `player/`.
- **The tooling for this session.**
  - The Gradle daemon a job's build starts inherits the job's output pipe and keeps it open, so a `cmd /c gradlew … > file` inside a runner job never returns. The baseline job hung that way and the user ended it. Later jobs start Gradle with `Start-Process … -PassThru` and `WaitForExit()`, with no redirected handles.
  - PowerShell pitfalls in the scratch scripts: `$Args` is an automatic variable (a parameter of that name was empty), `Sequence` is a reserved word, and a script-level `[string]$Pick` parameter type-constrained the variable `$pick` (names are case-insensitive).
- **Not checked:**
  - in `pnpm tauri dev`: the Watched badge appearing without a manual refresh. The app refetches the episodes on WATCHED and CLOSED (`realtime.spec.ts`), and the badge reads `isWatched` (`EntryItemList`), but this wasn't clicked through live. Stage 3 adds live progress, and checks it with the §6 criteria;
  - the backend's own shutdown while mpv plays: it quits mpv over IPC now (2 s, then the kill), as Stage 1's hook did with the kill alone. A unit test doesn't reach a shutdown hook, and it wasn't rerun live;
  - a quit past the threshold giving ENDED with `reason=quit` live: the tracker's tests cover it.


### Stage 3 (2026-11-11)

- **Baseline before the stage** (feat/phase-4-ipc merged as #28; all rerun before any edit):
  - server: `.\gradlew.bat build` green with 711 tests, 0 failures (core 640 with 8 skipped, AndroidCompat 7, ext-host 8, api 56);
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 34 files, 267 passed + 9 skipped; `pnpm lint`, `pnpm typecheck` and `pnpm format:check` clean;
  - the live spec: 9/9 against the seeded `live-data`, with 36 documents validated;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - server: 717 tests, 0 failures (core 640 with 8 skipped, AndroidCompat 7, ext-host 8, api 62). The 6 new ones are `EpisodeUpdateApiTest`;
  - smoke: 24/24 on the new jar;
  - app: 37 files, 307 passed + 9 skipped (40 new):
    - `VideoPicker.spec.ts` 13;
    - `progress.spec.ts` 9;
    - `videoChoices.spec.ts` 2;
    - `videos.spec.ts` 6 more;
    - `EntryItemList.spec.ts` 4 more (progress, live rows, selection, chapters not selectable);
    - `realtime.spec.ts` 4 (in place of the old playback test);
    - `AnimeDetailsView.spec.ts` (the Play tests rewritten for the picker, plus resume and three bulk-mark tests);
    - `entryItems.spec.ts` (progress and playing).

    `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
  - the live spec: 9/9 on the new jar, with 38 documents validated (`HOSTER_VIDEOS_MUTATION` and `UPDATE_EPISODES_MUTATION` are new);
  - `cargo test`: 17 (no Rust change).
- **The `LongString` scalar takes a string literal:** `lastSecondSeen: 60000` is a validation error, and `"60000"` is accepted. The app's `EpisodePatch.lastSecondSeen` is a string, as the field it sets.
- **Gradle:** `ktlintFormat` and `build`/`test` in one invocation fail on an implicit task dependency (`:core:generateJte` and `runKtlintFormatOverMainSourceSet`). Run them as two invocations.
- **Live through the API** (a scratch script driving the real jar and the installed mpv on the seeded `live-data`; mpv muted over a second IPC client). AnimeGG, One Piece ep. 3, one hoster with 5 videos (`[SUBBED] AnimeGG:1080p` is the source's pick):

  | Step | Result |
  |---|---|
  | `fetchHosterVideos` on that hoster (the picker's Load/Retry) | READY, 5 videos |
  | `playEpisode` with the non-default `[DUBBED] AnimeGG:360p` | session `videoTitle` the same; mpv `height` 360, its title the anime and episode, duration 1474.98 s |
  | a seek to halfway, `quit` at mpv's `time-pos` 740.033 | stored `lastSecondSeen` 740033, `totalSeconds` 1474977; CLOSED (EXITED, exit 0) |
  | the backend stopped, a new one started | stored 740033 (50.2 %), so the picker offers "Resume from 12:20"; the remembered choice found again by hoster name and title |
  | `playEpisode` with `startSeconds` = 740.033 (`lastSecondSeen / 1000`) | the first PROGRESS and mpv's `time-pos` at 740.03 s: **0.00 s off** |
  | a seek to 90 % | WATCHED; stored `isWatched` true |
  | `updateEpisodes` watched on 3 episodes, then unwatched on those and ep. 3 | all marked, then all unmarked with `lastSecondSeen` 0 |

  The `episodes` query read back the same values. No `token=` in `madoyomi.log`, `mpv.log`, `sidecar.log` or `sidecar.1.log`.
- **Live in `pnpm tauri dev`** (a second session; driven by clicks with computer use on the seeded `live-data`, with mpv muted and read over its named pipe by a second IPC client). AnimeGG, One Piece ep. 3 (id 3, unwatched, position 0 at the start), the list sorted ascending:

  | Step | Result |
  |---|---|
  | Play on ep. 3 | the picker: `no_hoster_list`, 5 videos; "Source's pick" on `[SUBBED] AnimeGG:1080p`, preselected; no resume footer (position 0), only Play |
  | `[DUBBED] AnimeGG:360p` chosen, Play | mpv opened in its own window; mpv `height` 360, duration 1474.98 s, its title the anime and episode; `madoyomi.log`: "episode 3, [DUBBED] AnimeGG:360p" |
  | a seek to 737 s over IPC | the row: "Playing", "12:10 left", then "12:06 left" 3 s later, with a bar (PROGRESS only) |
  | `quit` over IPC at `time-pos` 757.6 | mpv exit code 0; the row: "11:57 left" with a bar |
  | the app closed (window closed: the backend stopped with code 0, no mpv left), stored values read | `lastSecondSeen` 757666, `totalSeconds` 1474977, not watched |
  | `pnpm tauri dev` again, Play on ep. 3 | the row still "11:57 left"; the picker preselected `[DUBBED] AnimeGG:360p`, marked "Last used", with "You stopped at 12:37.", Start over and **Resume from 12:37** |
  | Resume | mpv's first `time-pos` 757.666: **0.00 s off** the stored position (§6: ±5 s) |
  | while playing; then `pause` over IPC | "Playing", "11:50 left" → "11:44 left"; then "Paused", "11:36 left" |
  | a seek to 1290 s (87 %) over IPC | "marked watched at 1290.0 s" in the log; the **Watched badge** on the row at once, while it still said "Playing" (§6) |
  | `quit` at 1315.4 s | mpv exit code 0; the row Watched, no progress line; the log: "ended (quit), next episode 4" |
  | ep. 4 clicked, ep. 6 Shift+clicked | "3 selected"; Mark watched: eps. 4–6 Watched, the selection cleared |
  | eps. 3–6 selected the same way, Mark unwatched | the badges gone; ep. 3 lost its progress line (position cleared) |
  | ep. 7 alone selected, "Mark previous as watched" | eps. 3–6 Watched (the earlier unwatched ones), ep. 7 not. Earlier, with ep. 4 or ep. 3 alone selected while every earlier episode was watched, the button was disabled |
  | eps. 3–6 unmarked again (the restore) | stored: eps. 1–2 watched (1499958 ms), eps. 3–7 unwatched with `lastSecondSeen` 0; 2 of 1167 episodes watched, as before the run |

  No `token=` in `madoyomi.log`, `sidecar.log`, `sidecar.1.log`, `mpv.log` or either run's `pnpm tauri dev` output. mpv wrote nothing into `player/`.
- **Stage 2's "quit past the threshold gives ENDED with `reason=quit`"** is now seen live: the quit at 1315.4 s logged "ended (quit), next episode 4".
- **The tooling for this session.** The agent's shell was the Linux VM again, so the checks and `pnpm tauri dev` ran through the job runner (`tools/dev-data/agent/`, gitignored), with scratch scripts to start and close the app, read mpv over IPC, and read the stored values with a server of their own while the app was closed. `pnpm` prints `[ELIFECYCLE] Command failed with exit code -1` when the app window is closed; the shell's own line before it is "backend stopped (exit code 0)".
- **Not checked:**
  - a lazy hoster's Load and a failed hoster's Retry in the UI: AnimeGG has a single READY hoster. `fetchHosterVideos` was checked through the API, and `VideoPicker.spec.ts` covers both buttons;
  - the subtitle and audio counts in the picker: AnimeGG's videos have none, and KickAssAnime wasn't played in this stage (`VideoPicker.spec.ts` covers them);
  - Start over and the Enter key on the preselected button: only Resume and Play were clicked (`VideoPicker.spec.ts`);
  - that a PROGRESS event refetches nothing was seen only as the row updating each second without the list flickering; `realtime.spec.ts` covers it, and a WebSocket reconnect forgetting the session is covered only there.

### Stage 4 (2026-11-11)

- **Baseline before the stage** (feat/phase-4-picker merged as #29; rerun before the server edits):
  - server: `.\gradlew.bat build` green with 717 tests, 0 failures (core 640 with 8 skipped, AndroidCompat 7, ext-host 8, api 62);
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 37 files, 307 passed + 9 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 9/9 with 38 documents validated;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - server: 733 tests, 0 failures (core 648 with 8 skipped, AndroidCompat 7, ext-host 8, api 70). The 16 new ones are `VideoChoicesTest` (8) and 8 `PlaybackApiTest` cases; two existing `PlaybackApiTest` cases now also check the retry;
  - smoke: 24/24 on the new jar;
  - app: 40 files, 325 passed + 9 skipped (18 new): `NextEpisodeToast.spec.ts` 2, `playback.spec.ts` 3, `playerPrefs.spec.ts` 2, `AnimeDetailsView.spec.ts` 8 (the countdown and auto-play, Play now with the source's pick, Cancel/another Play/leaving the page, Cancel during the start, auto-play off, the picker when it can't auto-play, "Retrying…", the picker after an ERROR), `VideoPicker.spec.ts` 2, `SettingsView.spec.ts` 1. `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
  - the live spec: 9/9 on the new jar, with 38 documents validated (the playback subscription has the new fields);
  - `cargo test`: 17 (no Rust change).
- **A flaky core test:** `ExtensionThreadsTest` ("source-pool threads created from extension code don't keep its class loader") failed once ("12, was 26"): it compares the shared source pool's size before and after, and idle threads of earlier tests timed out in between. It passed on the rerun, and this stage doesn't touch it.
- **mpv's log has the status, `end-file` doesn't.** A refused URL gives `end-file` with `reason: error` and `file_error: "loading failed"`, exit code 2, and `[ffmpeg] http: HTTP error 403 Forbidden` in `mpv.log` at warn level.
- **An expired URL mid-play is an `eof`.** With AnimeGG's MP4 through the proxy's expiring mode (403 ten seconds after the first request), a seek past the cache made mpv log the 403 and an "End of file" from FFmpeg, then end the file as `eof` with exit code 0, at the seek target (15:00 of 25:00). Before the rule in §9 this was ENDED with the next episode: the first live run showed it.
- **The header measurement** (the proxy's expiring mode without an expiry logs each token's request headers once; compared with the session's include file):

  | Source | Headers in the include file | Arrived verbatim | mpv's own |
  |---|---|---|---|
  | AnimeGG (MP4) | 2: `user-agent`, `referer` (the User-Agent has commas and semicolons) | 2 | `accept`, `range`, `icy-metadata`, `host`, `connection` |
  | KickAssAnime (HLS, the playlist request) | 7: `accept`, `accept-language` (`en-US,en;q=0.9`), `origin`, `sec-fetch-dest`, `sec-fetch-mode`, `sec-fetch-site`, `user-agent` | 7 | `range`, `icy-metadata`, `host`, `connection` (the source's `accept` replaced mpv's) |

  The first two runs of the script reported 0: it read the proxy's log before the wrapper had logged the launch's token. The comparison now waits for it.
- **Live through the API** (a scratch script driving the real jar, with a scratch mpv wrapper as `MADOYOMI_MPV` that sends the URL through the header proxy's expiring mode by plan; AnimeGG, One Piece ep. 3, `[SUBBED] AnimeGG:1080p`):

  | Case | Events | Result |
  |---|---|---|
  | refused at once (403), from 60 s | STARTED → RETRYING (`loading failed (HTTP 403)`, `httpStatus` 403) → CLOSED (exit 2) → STARTED (the new session) → PROGRESS | the same video, first position 60.00 s and mpv's `time-pos` 60.00 (asked 60) |
  | expires 10 s after the first request; played from 120 s, a seek to 900 s after 11 s | STARTED → PROGRESS ×15 → RETRYING ("mpv stopped reading the video at 15:00 of 25:00 (HTTP 403)") → CLOSED (exit 0) → STARTED → PROGRESS | the new session started at 900.00 s (mpv `time-pos` 900.00) |
  | refused, and refused again after the refetch | STARTED → RETRYING → CLOSED → STARTED → ERROR (`retried`, `httpStatus` 403, `nextVideo` `[SUBBED] AnimeGG:720p`) → CLOSED | nothing plays; the next video is the one after the failed one |

  The proxy logged the 403s with the requests' ranges. Each run put ep. 3 back to unwatched with position 0.
- **Live in `pnpm tauri dev`** (driven by clicks with computer use on the seeded `live-data`, with the same wrapper as `MADOYOMI_MPV`, passing URLs through unless a plan line said otherwise; mpv muted and seeked over its pipe). AnimeGG, One Piece, the remembered choice `[DUBBED] AnimeGG:360p`:

  | Step | Result |
  |---|---|
  | Settings → Playback | "Auto-play the next episode", on |
  | Play on ep. 3, the picker's preselected `[DUBBED] AnimeGG:360p`, a seek to 8 s before the end | ENDED (eof, next episode 4) at 23:30:02.6; the toast; episode 4 started at 23:30:14.3 (the 10 s countdown and the fetch) with `[DUBBED] AnimeGG:360p`, no picker; mpv's title "One Piece - Episode 4 …", the row "Playing" |
  | ep. 4 to its end; the toast already "Starting…"; Cancel | **found:** the toast went away but episode 5 played: Cancel only dropped a start still fetching the videos, and this one's `playEpisode` was already sent. Fixed: a Cancel (or leaving the page) during the start stops what it started once `playEpisode` returns; opening the picker only dismisses (§9). A test was added |
  | ep. 5 resumed from 0:35, to its end | "Next: Episode 6 …", "Plays in 10 s", counting down; Cancel at "Plays in 2 s": nothing played in the next 15 s |
  | ep. 5 again, to its end; Play now, then Cancel straight after | the first two tries after the fix still played episode 6, and no stop was logged. With an on-screen trace, two tries in a row went Play now → Cancel 0.2 s later → the fetch returned 0.4 s after that and was dropped, and nothing played; once more without the trace, the same. The first two weren't explained: the whole start took under a second, and the trace wasn't there yet. The case of a `playEpisode` already sent is only covered by `AnimeDetailsView.spec.ts` |
  | Play now (in the tries above) | the next episode started within a second, with the remembered video |
  | ep. 6, the first URL refused (403), Resume from 3:05 | "loading failed (HTTP 403); fetching the videos again", and 0.5 s later the same `[DUBBED] AnimeGG:360p` from the resume point (`time-pos` 193.6 s soon after). "Retrying…" lasted those 0.5 s, too short to see on screen (`AnimeDetailsView.spec.ts` covers it) |
  | ep. 7, refused, and refused again after the refetch | the picker reopened: "Couldn't play [DUBBED] AnimeGG:360p", "mpv could not play the video: loading failed (HTTP 403)", "[SUBBED] AnimeGG:1080p is selected instead" (the next one, wrapping around); its Play: mpv `height` 1080 |
  | the restore | `[DUBBED] AnimeGG:360p` played once on ep. 7 (the remembered choice back), then eps. 3–7 marked unwatched in bulk. Stored: eps. 1–2 watched (1499958 ms), eps. 3–7 unwatched with `lastSecondSeen` 0 (their durations are now known); 2 of 1167 episodes watched |

  No `token=` in `madoyomi.log`, `sidecar.log`, `sidecar.1.log`, `mpv.log` or the `pnpm tauri dev` output. The backend stopped with code 0 when the window closed, and mpv wrote nothing into `player/`.
- **The tooling for this session.** The agent's shell was the Linux VM again, so builds, tests and `pnpm tauri dev` ran through the job runner (`tools/dev-data/agent/`, gitignored). An earlier session wrote most of this stage's code and the API live checks; this one fixed a test name the Kotlin compiler rejects on Windows (a `:` in a backticked name), the header measurement's script, and the Cancel bug, and ran the rest. Computer use's "open application" on the app's exe started a second instance on the default data dir (`%APPDATA%\Madoyomi`); it was closed at once. The `pnpm tauri dev` window is already running and only needs a screenshot.
- **Not checked:**
  - Cancel live while a `playEpisode` is in flight (the start took under a second); `AnimeDetailsView.spec.ts` covers it;
  - auto-play switched off, live: `AnimeDetailsView.spec.ts` and `SettingsView.spec.ts` cover the toast without a countdown and the switch;
  - "Retrying…" on screen (too short with a local refusal), and leaving the page during the countdown (`AnimeDetailsView.spec.ts`);
  - a 404 or an expiring HLS stream: the proxy's expiring mode served AnimeGG's MP4 only. KickAssAnime's HLS goes through the proxy only for its playlist (the segments' relative URLs would miss it); the step 20 matrix in the exit run has expiring URLs.

### Stage 5 (2026-11-12)

- **Baseline before the stage** (feat/phase-4-next merged as #30; rerun before any edit):
  - server: `.\gradlew.bat build` green with 733 tests, 0 failures (core 648 with 8 skipped, AndroidCompat 7, ext-host 8, api 70), on the second run. The first failed two core tests that this stage doesn't touch: `ExtensionThreadsTest` (the flaky one, below) and upstream's `CefTest`, which downloads a JBR from GitHub and got `IOException: proxy.example`, the host `SocksProxyTest` sets on the shared `serverConfig` for a moment;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 40 files (39 + 1 skipped), 325 passed + 9 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 9/9 with 38 documents validated;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - server: 766 tests, 0 failures (core 674 with 8 skipped, AndroidCompat 7, ext-host 8, api 77). The 33 new ones:
    - core: `VideoPreferencesTest` 7, `TrackSelectionTest` 7, `PlayerSettingsTest` 5, `PlayerLanguagesTest` 3, `MpvLocatorTest` 3 more, `PlaybackTrackerTest` 1 more;
    - api: `PlayerSettingsApiTest` 4, `PlaybackApiTest` 3 more (the languages over a fake mpv with tracks of its own, the threshold, a wrong mpv path). The Stage 2 track test now expects `auto` and the `sid`/`aid` set after the tracks load;
  - smoke: 24/24 on the new jar;
  - app: 40 files, 338 passed + 9 skipped (13 new): `videos.spec.ts` 4, `VideoPicker.spec.ts` 3, `SettingsView.spec.ts` 4, `AnimeDetailsView.spec.ts` 2. `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
  - the live spec: 9/9 on the new jar, with 42 documents validated (`PLAYER_SETTINGS_QUERY`, `SET_PLAYER_SETTINGS_MUTATION`, `PLAYER_LANGUAGES_QUERY` and `MPV_CHECK_QUERY` are new; the hoster fragment has `sourceIndex` and `matchesPreference`);
  - `cargo test`: 17 (no Rust change).
- **Two flaky core tests, neither touched here.** `ExtensionThreadsTest`'s "source-pool threads … don't keep its class loader" failed in 3 of the stage's 7 builds, the first baseline run included (before any edit): "the extension's code created pool threads (3, was 19)". It counts the shared source pool's threads before and after starting three tasks, but tasks reuse idle threads that earlier tests left (they idle for 60 s), and idle ones time out meanwhile, so the count can drop without anything leaking. The leak check after it (`GcAssert`) is what matters. Upstream's `CefTest` failed in 2 of 7 ("proxy.example", "bad host"): it downloads a JBR live while `SocksProxyTest` briefly sets those hosts on the shared `serverConfig`. Neither is changed here; the exit run can make the first count its own threads by name. The final build was green.
- **A test name with `;`** doesn't compile on Windows either ("Name contains illegal characters"), as `:` didn't in Stage 4.
- **Live through the API** (a scratch script on the seeded `live-data`, the real jar and the installed mpv, muted over a second IPC client):

  | Case | Result |
  |---|---|
  | AnimeGG ep. 3, no preference | the source's order: `[SUBBED] AnimeGG:1080p`, 720p, 480p, 360p, `[DUBBED] AnimeGG:360p`; `sourceIndex` = `index`, nothing `matchesPreference` (AnimeGG sets no `resolution`: the height comes from the title) |
  | the same, preferred quality 720p | `[SUBBED] AnimeGG:720p` first (`sourceIndex` 1, matches), the rest in the source's order |
  | the same, 2160p (not there) | `[SUBBED] AnimeGG:1080p` first and matching: the highest below |
  | KickAssAnime BSD5 ep. 1 (one `no_hoster_list` hoster, "VidStreaming - 1080p (1920x1080) - 1.69 Mbps" and so on), 720p | the 720p video first |
  | threshold 60 %, AnimeGG ep. 3 from 60 s, a seek to 62 % (930 s of 1500.08 s) | WATCHED at 930.0 s; stored `isWatched` true, `lastSecondSeen` 930000; then unmarked (position 0) and the threshold put back |
  | KickAssAnime ep. 1168 (8 subtitles, "English (eng)" … "Arabic (ara)"; 2 audio, "Japanese", "English"), no language | `sid` = "English (eng)" (the source's first; mpv's track 5, the order they loaded), `aid` = "Japanese" (the source's first: no audio of its own) |
  | subtitles Spanish, audio English | `sid` = "Spanish (spa)", `aid` = "English" |
  | subtitles off, audio Japanese | `sid` none, `aid` = "Japanese" |
  | mpv path `C:\nowhere\mpv.exe` | `playEpisode`: `PLAYER_NOT_FOUND` "mpv was not found at C:\nowhere\mpv.exe (set in Settings → Player)"; `mpvCheck`: not ok, origin `SETTINGS` |
  | `C:\Program Files\MPV Player\mpv.exe` | `mpvCheck` ok, origin `SETTINGS`, "mpv v0.41.0-244-gaf9c81fa1 …"; the episode played with that mpv (`Win32_Process`) |
  | cleared | `mpvCheck` ok, origin `PATH`, the same mpv |

- **Live in `pnpm tauri dev`** (driven by clicks with computer use on the seeded `live-data`; mpv muted and read over its pipe; episode 4's position set to 1327.6 s of 1475.1 s, 90 %, before the start):

  | Step | Result |
  |---|---|
  | Settings | "Player": auto-play on, "Mark as watched at" 85 %, the source's choice, any server, the source's first subtitles, the source's audio, and "mpv v0.41.0-244-gaf9c81fa1 … C:\Program Files\MPV Player\mpv.exe (from PATH)" |
  | 95 %, then Play on ep. 4 (90 %) | `player-settings.json` had `0.95` at once; the picker offered **Resume from 22:07** ("You stopped at 22:07") |
  | 85 % and 720p, Play on ep. 4 again | no resume prompt (90 % is past 85 %), only Play. The order: `[SUBBED] AnimeGG:720p` **Preferred**, `[SUBBED] AnimeGG:1080p` **Source's pick**, 480p, 360p, and `[DUBBED] AnimeGG:360p` **Last used**, preselected (the remembered choice first) |
  | Play, a seek to 8 s before the end | ENDED (eof, next episode 5); 10.7 s later auto-play played ep. 5 with `[DUBBED] AnimeGG:360p`, the remembered choice over the 720p preference |
  | subtitles Spanish, audio English; KickAssAnime BSD5 Ep. 1 (no remembered choice on this device) | the picker preselected "VidStreaming - 720p (1280x720) - 836 kbps", marked Preferred ("8 subtitles · 2 audio tracks"; Source's pick on 1080p). Played: mpv `height` 720, `sid` "Spanish (spa)", `aid` "English"; the log: "subtitles track 3, audio track 1" |
  | mpv path `C:\nowhere\mpv.exe`, Enter | "mpv was not found at C:\nowhere\mpv.exe (set in Settings → Player)" under the field; Play on BSD5 Ep. 1: "Couldn't play the episode", "mpv wasn't found. Install mpv and put it on PATH, or set its path in Settings → Player.", `PLAYER_NOT_FOUND` with the message |
  | `"C:\Program Files\MPV Player\mpv.exe"` (quoted), Enter | the field without the quotes, "mpv v0.41.0-… (from this setting)"; Play resumed BSD5 Ep. 1 with that mpv |
  | the field cleared, and quality, subtitles and audio back to the source's | "(from PATH)"; the file at the defaults |
  | the restore (a server of its own, the app closed) | eps. 1–2 watched (1499958 ms), eps. 3–7 unwatched with `lastSecondSeen` 0; BSD5 Ep. 1 back to 2001 ms; 2 of 1167 One Piece episodes watched |

  No `token=` in `madoyomi.log`, `sidecar.log`, `sidecar.1.log`, `mpv.log` or the `pnpm tauri dev` output; the backend stopped with code 0 when the window closed; mpv wrote nothing into `player/`. The webview still remembers `[DUBBED] AnimeGG:360p` for AnimeGG (auto-play remembered it again) and has auto-play on; it now also remembers "VidStreaming - 720p" for KickAssAnime, from the Play above.
- **The tooling for this session.** The agent's shell was the Linux VM again, so builds, tests, the API checks and `pnpm tauri dev` ran through the job runner (`tools/dev-data/agent/`, gitignored: `liveplay5.ps1` is this stage's script). A native `<select>`'s drop-down is a window of its own that computer use masks, so the selects were changed with the keyboard (End, Up, Home, typing a letter, Enter). Text pasted with a newline doesn't press Enter in the field: Enter was pressed after it. A scratch variable named `$proc` in the script replaced `MadoyomiServer.ps1`'s own, so its server stop failed (the server exited with the job anyway); renamed.
- **Not checked:**
  - a preferred server that reorders hosters live: AnimeGG and KickAssAnime each have a single `no_hoster_list` hoster, one server in their titles ("AnimeGG", "VidStreaming"). `VideoPreferencesTest` and `PlayerSettingsApiTest` (two hosters, "Doodstream" and "VidStreaming") cover it;
  - the file's own tracks chosen by language live (an MKV with ASS and several audio tracks): `TrackSelectionTest` and `PlaybackApiTest` (a fake mpv with a Japanese audio track of its own) cover it; the exit run's format matrix has an MKV;
  - a threshold change picked up by the session that plays, live: `PlaybackTrackerTest` covers it; live, the threshold was set before the play;
  - auto-play playing the preference's pick (no remembered choice) live: every source played in `pnpm tauri dev` has a remembered choice once played from the picker; `AnimeDetailsView.spec.ts` covers it;
  - a refused setting in the UI (`BAD_REQUEST`): the selects only offer valid values; `SettingsView.spec.ts` covers the rollback.

### Stage 6 (2026-11-12): exit run

- **Baseline before the stage** (feat/phase-4-settings merged as #31; rerun before any edit to the server or the app):
  - server: `.\gradlew.bat build` green with 766 tests, 0 failures (core 674 with 9 skipped, AndroidCompat 7, ext-host 8, api 77), on the second run. The first failed upstream's `CefTest` only (`UnknownHostException` through a SOCKS proxy: `SocksProxyTest`'s host on the shared config, as in Stage 5), so api's tests didn't run; `build --continue` passed. Core's skip count is 9 here and 8 in the final build, as in Stage 1;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24;
  - app: 40 files (39 + 1 skipped), 338 passed + 9 skipped; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` clean;
  - the live spec: 9/9 with 42 documents;
  - `cargo test`: 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.
- **After the stage:**
  - server: 768 tests, 0 failures (core 675 with 8 skipped, AndroidCompat 7, ext-host 8, api 78), green on the first build. The 2 new ones: `PlaybackTrackerTest` "a file that never played is told apart from one that failed while playing", and `PlaybackApiTest` "a stream that never played a frame is fetched again once, though mpv reported its start position" (`FakeMpvServer` got `fakempv-nothing`). `ExtensionThreadsTest` (now counting by name) passed;
  - smoke: 24/24 on the new jar;
  - the live spec: 9/9 on the new jar, with 42 documents;
  - app: unchanged, rerun: 40 files, 338 passed + 9 skipped, and lint, typecheck, format:check and build clean;
  - `cargo test`: 17, with build, clippy and fmt clean (no Rust change).
- **The format matrix** (step 20), live through the API on the fresh data dir of the first §6 run (`liveplay6.ps1`, scratch), on the new jar for every row (MP4, MKV, AnimeOnsen and the MP4 expiry ran on both jars, with the same results). mpv was the real one behind the scratch wrapper (`MADOYOMI_MPV`), muted and read over its pipe. "Headers" compares the session's include file with what the server at the other end received (the header proxy, or the MKV's local server):

  | Row | What played | Plays | Tracks (`sid`/`aid`, from `track-list`) | Progress | Headers |
  |---|---|---|---|---|---|
  | MP4 | AnimeGG, One Piece ep. 10, `[SUBBED] AnimeGG:1080p`, from 60 s, through the proxy | `time-pos` 60 → 63.06, height 1080, duration 1500.17 s | its own AAC (`aid` 1), no subtitles | a seek to halfway, `quit` at 751.502: stored 751501 / 1500170 ms | 2 of 2 verbatim (`user-agent`, `referer`) |
  | HLS | KickAssAnime, Bungou Stray Dogs 5th Season ep. 1, "VidStreaming - 1080p (1920x1080) - 1.69 Mbps" | height 1080, duration 1420.05 s | 8 external WebVTT subtitles, 2 external AAC (no audio of its own) | a seek to 600 s, `quit` at 604.771: stored 604771 / 1420045 ms | 7 of 7 verbatim on the playlist request (`accept`, `accept-language`, `origin`, `sec-fetch-*`, `user-agent`) |
  | External subtitles | KickAssAnime (above) | | no language: "English (eng)", the source's first; `es`: "Spanish (spa)"; off: `sid` no | | |
  | | AnimeOnsen, "DARK MACHINE -The Animation-" ep. 1, "Default (720p)" (DASH) | height 720, duration 1370 s | 9 external **ASS** tracks ("English", "Español", "Русский", …); `en`: "English", codec `ass`, its `sub-text` shown | `quit` at 41.15: stored 41147 / 1370000 ms | |
  | Multiple audio, external | KickAssAnime (above) | | no language: "Japanese", the source's first; `en`: "English"; `ja`: "Japanese" | | |
  | MKV with ASS, audio of its own | a generated MKV (600 s; H.264; AAC "Japanese" `jpn` and "English dub" `eng`; ASS "English (ASS)" `eng` and "Espanol (ASS)" `spa`) in place of AnimeGG ep. 11's `[SUBBED] AnimeGG:1080p` | height 360, duration 600.02 s | 4 tracks of its own. No language: `aid` 1 Japanese, `sid` 1 English (ASS) (the file's defaults); `es`/`en`: "Espanol (ASS)" and "English dub"; off/`ja`: `sid` no, "Japanese" | a seek to 300 s, `quit`: stored 303023 / 600023 ms; a seek to 570 s: WATCHED, ENDED (eof, next episode 12), stored 599940 ms | 2 of 2 of AnimeGG's verbatim at the MKV's server |
  | Expiring MP4, at once | AnimeGG ep. 12, the proxy answering 403 from the start | RETRYING (`loading failed (HTTP 403)`), then the same video from the asked 60 s: first position 60.00, `time-pos` 60.00 | | | |
  | Expiring MP4, mid-play | the URL expires 10 s after its first request; from 120 s, a seek to 900 s after 11 s | RETRYING "mpv stopped reading the video at 15:00 of 25:00 (HTTP 403)", then the new session at 900.00 | | stored 900000 ms | |
  | Expiring MP4, twice | refused, and refused again after the refetch | ERROR, `retried`, HTTP 403, `nextVideo` `[SUBBED] AnimeGG:720p` | | | |
  | Expiring HLS, at once | KickAssAnime ep. 2, its playlist refused | RETRYING (HTTP 403), then the same video at 30.00 s | English and Japanese selected again | | |
  | Expiring HLS, mid-play | a generated HLS (the MKV's video and first audio, 59 TS segments) through the proxy in place of ep. 2's URL; the token answers 6 requests, then 403 | played to 41.6 s, then RETRYING "mpv stopped reading the video at 0:41 of 9:59 (HTTP 403)", and the new session (a fresh token) started at 41.61. The proxy: 6 answered and 64 refused, then 59 answered | | | |

  No `token=` in the data dirs' `madoyomi.log`, `sidecar.log`, `sidecar.1.log` and `mpv.log`, the proxy's or the MKV server's log, or the `pnpm tauri dev` output. No mpv was left after any run, and mpv wrote nothing into `player/`.
- **Found in the matrix and fixed: a stream that never played a frame wasn't fetched again.** KickAssAnime's HLS through the header proxy opened its playlist, but no segment ever played (below). mpv reported `time-pos` 0 (its start position) once the file was open, then ended it with `file_error` "no audio or video data played" and exit code 2, with no HTTP status in its log. Stage 4's "early" meant "no position reported", so this was ERROR at once, with the next video. Now it is RETRYING, and the same run played the refetched video directly (STARTED → RETRYING → CLOSED → STARTED → PROGRESS). §9.
- **KickAssAnime's segments can't go through the proxy.** Its playlist host answers the proxy, but the segment hosts (`st1.*.xyz`, behind Cloudflare, segments named `.jpg`) refuse Node's client and curl with a 403 challenge page, while mpv's own requests pass. So its HLS only goes through the proxy for the header measurement (the playlist), and the mid-play HLS expiry used a generated stream.
- **The header proxy, for this stage** (`spikes/mpv-play/header-proxy.mjs`, tracked): in the expiring mode it rewrites an HLS playlist (found by its type, its name, or a small body starting with `#EXTM3U`) so that every URI goes through the same token, absolute, with the upstream file name kept in the path, and it doesn't pass on an `image/*` type (segments served as `.jpg`). `PROXY_DEBUG=1` logs each request and playlist.
- **The §6 criteria from a fresh data dir**, live in `pnpm tauri dev`, through the UI (clicks, keys and pasted text with computer use; mpv muted and seeked over its pipe by a script; its window closed with its own close button). The webview's device preferences carried over (auto-play on, `[DUBBED] AnimeGG:360p` and "VidStreaming - 720p" remembered, KickAssAnime pinned), as decided in Phase 3. First run, `fresh-data`, before the fix:

  | Step | Result |
  |---|---|
  | first start | healthy after 9.4 s; the Library empty; Extensions: "No anime repo yet. Madoyomi ships with none" |
  | Settings → Player | the defaults: auto-play on, "Mark as watched at" 85 %, the source's choice, any server, the source's first subtitles, the source's audio, "mpv v0.41.0-244-gaf9c81fa1 … (from PATH)"; no `player-settings.json` yet |
  | yuzono's `index.min.json` | "an anime repo (type detected), with 250 extensions", stored as `…/raw/repo/index.pb`, "Signing key trusted" |
  | AnimeGG 14.3, KickAssAnime 14.64, AnimeOnsen 14.11 | each installed, with DOWNLOADING → VERIFYING → CONVERTING → INSTALLED under Live events |
  | the trust prompt | removing yuzono (its confirmation) left the three "Untrusted"; Trust… on AnimeGG showed the package, 14.3, "No added repo lists it any more" with the stored URL, the hash `cbec121a…5905` and the warning; "Trust and load it" loaded all three (one certificate). yuzono was added again |
  | One Piece (AnimeGG), Add to library, Play on ep. 1 | the picker: 5 videos, Source's pick `[SUBBED] AnimeGG:1080p`, Last used `[DUBBED] AnimeGG:360p` preselected, no resume footer; Play: mpv "One Piece - Episode 1 - …", height 360 |
  | a seek to 740 s, then mpv's window closed (its ×) | mpv exit code 0; the row "12:02 left" |
  | the app closed, the stored values read, the app started again | backend exit 0, no mpv left; stored 752966 / 1475001 ms; the picker: "You stopped at 12:32", **Resume from 12:32** |
  | Resume | mpv's first `time-pos` **752.966**: 0.00 s off |
  | a seek to 1290 s (87 %) | "episode 1 marked watched at 1290.0 s"; the **Watched** badge on the row while it said "Playing" |
  | a seek to 1467 s | ENDED (eof, next episode 2) at 10:58:28.3; "Next: Episode 2 - The Great Swordsman Appears! …", "Plays in 6 s"; episode 2 at 10:58:39.1 with `[DUBBED] AnimeGG:360p`, no picker |
  | "Mark as watched at" → 70 % while episode 2 played | `player-settings.json` had `0.7` at once; a seek to 1062 s (72 %): "marked watched at 1062.0 s" and the badge in the same session (Stage 5's "a threshold change picked up mid-session", now seen live) |
  | `quit` at 1077 s (past 70 %) | "Next: Episode 3 …"; Cancel: nothing played |
  | Bungou Stray Dogs 5th Season (KickAssAnime), Add to library | 11 episodes (for the matrix) |

  Second run, `fresh-data2`, after the fix round, on the new jar:

  | Step | Result |
  |---|---|
  | first start, Settings → Player | healthy after 9.0 s; the same defaults |
  | yuzono added, AnimeGG and KickAssAnime installed | as above; trusted by the repo's key, so no prompt |
  | One Piece, Play on ep. 1, **Enter** | the preselected `[DUBBED] AnimeGG:360p` played (Stage 3's "Enter on the preselected button", now seen live) |
  | a seek to 700 s, mpv's window closed; the app closed and started again | stored 716500 ms; "Resume from 11:56"; mpv's first `time-pos` **716.500**: 0.00 s off |
  | a seek to 1290 s | the Watched badge while playing |
  | a seek to 1467 s | ENDED at 11:51:35.0; the toast; episode 2 at 11:51:49.3 (the countdown and a 4 s fetch), no picker |
  | KickAssAnime BSD5 Ep. 1, Play | the picker: three videos, each "8 subtitles · 2 audio tracks", 1080p the Source's pick, 720p Last used; Play: height 720, 11 tracks, "subtitles track 1, audio track 1" (English, Japanese) |

  Each time the window closed, the shell logged "backend stopped (exit code 0)" and no mpv was left.
- **The tooling for this session.** The agent's shell was the Linux VM again, so builds, tests, the API checks and `pnpm tauri dev` ran through the job runner (`tools/dev-data/agent/`, gitignored): `liveplay6.ps1` (the matrix), `app-start6.ps1` (a data dir by name), `front.ps1` (brings the window forward: computer use can't click while the desktop is in front, and its "open application" would start a second app), `ipcdo.ps1`, `firstpos.ps1`, `stored6.ps1`, `media-serve.mjs` (the generated files, with ranges, logging each file's request headers), and `mpvwrap` with a new `url=<url>` plan line. The MKV and HLS were made with FFmpeg in the VM into `tools/dev-data/agent/media/`. Two script pitfalls: the backend's WebSocket closes after about 30 s without a message ("Connection Idle Timeout"; the app pings every 20 s), so the script pings too; and `Write-Output` inside a function whose output is discarded hides the message.
- **Not checked, or not coverable:**
  - an MKV from a real source: none of the top 20 serves one (above), so the row uses a generated file; a real one would differ only in its URL and headers;
  - a mid-play expiry of KickAssAnime's own HLS: its segment hosts refuse the proxy (above); the generated HLS covered the backend's side;
  - the trust prompt on an install itself: every yuzono extension is signed with the repo's key, which adding the repo trusts, so an install from it never prompts; the prompt was shown by removing the repo, as in Phase 3's exit run. `TrustDialog.spec.ts` and `ExtensionsView.spec.ts` cover the prompt after an install;
  - a preferred server reordering hosters live: AnimeGG, KickAssAnime and AnimeOnsen each give a single `no_hoster_list` hoster (`VideoPreferencesTest`, `PlayerSettingsApiTest`);
  - auto-play switched off, "Retrying…" on screen, and Cancel while a `playEpisode` is in flight: as in Stage 4, covered by `AnimeDetailsView.spec.ts`;
  - the embedded `--wid` prototype: the stretch goal, not part of the exit.
