# Phase 4 — Playback

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 3](phase-3-frontend-mvp.md) · Next: [Phase 5](phase-5-manga-reader.md)

## 1. Goal

Watch episodes reliably: pick a video/server/quality from `getVideoList`, launch mpv with the right headers, subtitles and audio tracks, track progress over JSON IPC, resume where the user stopped, mark episodes watched, and go to the next episode. mpv runs in its own window for the MVP; embedding it in the app window is a stretch goal.

## 2. Depends on / inputs

- Phase 2: `GET /api/episodes/{id}/videos` returns `Video` with headers, subtitle and audio tracks.
- Phase 3: Details screen with the episode list.
- Phase 0 S4: working mpv command line and named-pipe IPC notes.
- `mpv.exe` available in `tools/bin/` for dev (bundled in Phase 7).

## 3. Deliverables

- `server/core/player/`: `MpvLauncher`, `MpvIpcClient` (named pipe, JSON IPC), `PlaybackSession` manager.
- `player/`: `mpv.conf`, `input.conf`, optional `uosc` scripts, and an IPC contract doc (`player/IPC.md`).
- DB: `episode_progress` (last position, duration, watched flag, updated at).
- API: `POST /api/player/play`, `POST /api/player/{session}/command`, `GET /api/player/{session}`; WS events `playback.progress`, `playback.ended`, `playback.error`.
- UI: video picker dialog, "Resume from mm:ss" / "Start over", watched indicators, next-episode prompt.
- Settings: watched threshold (default 85%), preferred quality, preferred subtitle/audio language, mpv path override.

## 4. Detailed steps

### Backend — launching mpv
1. `MpvLauncher.build(video, options)`: URL, `--http-header-fields` (escaped, comma-joined), `--referrer`/`--user-agent` when present, `--sub-files` (with `--sub-file-paths`), `--audio-files`, `--force-media-title`, `--start=<resume>`, `--input-ipc-server=\\.\pipe\madoyomi-mpv-<session>`, `--config-dir=<player dir>`.
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
- [ ] `MpvLauncher` builds args (headers, subs, audio, title, start, IPC pipe)
- [ ] mpv spawned + tracked + killed on shutdown
- [ ] `player/mpv.conf` + `input.conf` in place

### IPC & progress
- [ ] Named-pipe JSON IPC client with `observe_property`
- [ ] Progress saved periodically and on quit
- [ ] Watched threshold marks the episode once
- [ ] `playback.*` WS events

### Selection & errors
- [ ] Preferred quality/server sorting
- [ ] Lazy `videoUrlParse` resolution
- [ ] 403/expired retry + fallback to next video
- [ ] Proxy fallback for headers mpv can't send

### Frontend
- [ ] `VideoPicker` dialog with remembered choice
- [ ] Resume / start-over prompt
- [ ] Progress + watched state in episode list; bulk mark
- [ ] Next-episode toast / auto-play setting
- [ ] Player settings section

### Tests
- [ ] Launcher argument unit tests
- [ ] IPC client test with fake pipe
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

- mpv in a separate window (MVP) vs. embedded: confirm the UX priority (PLAN.md §7).
- Whether the backend or Tauri owns the mpv process (default: backend).
