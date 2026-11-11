# Phase 4 — Playback

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 3](phase-3-frontend-mvp.md) · Next: [Phase 5](phase-5-manga-reader.md)

## 1. Goal

Watch episodes reliably: pick a video/server/quality from `getVideoList`, launch mpv with the right headers, subtitles and audio tracks, track progress over JSON IPC, resume where the user stopped, mark episodes watched, and go to the next episode. mpv runs in its own window for the MVP; embedding it in the app window is a stretch goal.

## 2. Depends on / inputs

- Phase 2: `GET /api/episodes/{id}/videos` returns `Video` with headers, subtitle and audio tracks.
- Phase 3: Details screen with the episode list.
- Phase 0 S4: working mpv command line and named-pipe IPC notes.
- mpv: in dev, `tools/bin/mpv/mpv.exe` if present, else `mpv.exe` on PATH (bundled in Phase 7; §9 "Where mpv comes from").

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
- [ ] Preferred quality/server sorting
- [x] Lazy `videoUrlParse` resolution (Stage 1: `playEpisode` resolves the video right before the launch, through Phase 2's `resolveVideo`; `PlaybackApiTest`)
- [ ] 403/expired retry + fallback to next video
- [ ] Proxy fallback for headers mpv can't send

### Frontend
- [ ] `VideoPicker` dialog with remembered choice
- [ ] Resume / start-over prompt
- [ ] Progress + watched state in episode list; bulk mark
- [ ] Next-episode toast / auto-play setting
- [ ] Player settings section

### Tests
- [x] Launcher argument unit tests (Stage 1: `MpvCommandTest`, `MpvLocatorTest`)
- [x] IPC client test with fake pipe (Stage 2: `MpvIpcClientTest` against `FakeMpvServer`, a fake mpv on a real Windows named pipe, which `PlaybackApiTest` also runs as mpv)
- [ ] Manual format matrix passed

### Stretch
- [ ] Embedded mpv via `--wid` prototype

## 6. Exit criteria

- [ ] Watch an episode from the UI, close mpv halfway, reopen the app, and resume within ±5 s of where you stopped.
- [ ] An episode passing the watched threshold shows as watched in the UI without a refresh.
- [ ] The next episode starts from the end-of-episode prompt.

## 7. Risks & notes

- Video URLs needing headers/cookies → 403 (PLAN.md §6, "Video URLs require headers/cookies"). Always pass `Video.headers`; the proxy is the fallback.
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
4. **Next episode and errors** (steps 8, 9, 11, 15): the next-episode toast with a countdown and auto-play; on a 403/404 or an early exit, the videos fetched again once, then the next video offered. The proxy fallback is decided by measurement (mpv sends every header itself, so it is likely deferred). Verified with an expiring URL from the header proxy (`spikes/mpv-play/header-proxy.mjs`) and live.
5. **Player settings** (steps 9, 16): the watched threshold, preferred quality, server and subtitle/audio language (sorting), and the mpv path override, in a Settings section. Verified by unit tests and a live change that takes effect.
6. **Exit run**: the step 20 format matrix (MP4, HLS, MKV with ASS, external subtitles, multiple audio tracks, expiring URLs), every §6 criterion from a fresh data dir, and a fix round. The embedded `--wid` prototype (step 17) stays a stretch goal after the exit, not part of it.

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

