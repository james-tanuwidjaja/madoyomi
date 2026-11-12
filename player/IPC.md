# Player contract: how the backend drives mpv

Madoyomi plays episodes in **mpv, in its own window**, and the **backend owns the process** (Phase 4 §9). The UI asks
the backend to play (`playEpisode`) and the backend does the rest. This file is the contract between the backend
(`server/core/src/main/kotlin/madoyomi/core/player/`) and mpv: the command line and include file (Stage 1), then the
IPC pipe, what is observed and when progress is saved (Stage 2), what happens when mpv can't open the video
(Stage 4, with Stage 6's "never played"), and which tracks it plays (Stage 5).

## Finding mpv and its config

| What | Where from (first match) |
| --- | --- |
| `mpv.exe` | the player settings' path (Settings → Player, `<data dir>/player-settings.json`; Stage 5) → `MADOYOMI_MPV` → `mpv.exe` on `PATH` |
| config dir (`--config-dir`) | `MADOYOMI_PLAYER_DIR` if it is a directory → `<data dir>/player` (created empty) |

The Tauri shell sets `MADOYOMI_MPV` to the repo's `tools/bin/mpv/mpv.exe` in dev and to the bundled
`resources/mpv/mpv.exe` in release (Phase 7), when the file is there; otherwise it sets nothing and the backend uses
`PATH`. It sets `MADOYOMI_PLAYER_DIR` to this directory (`player/`) in dev and to `resources/player` in release. A
missing mpv is the error `PLAYER_NOT_FOUND` (its `details.origin` says which of the three named it: `SETTINGS`,
`ENV`, …); a launch that fails is `PLAYER_FAILED`. The settings' check (`mpvCheck`) runs `<mpv> --version` and wants a
line starting with "mpv ".

## The command line

Only the backend's own values are on the command line:

```
mpv.exe --config-dir=<player dir> --include=<data dir>\cache\player\<session>.conf
        --input-ipc-server=\\.\pipe\madoyomi-mpv-<session> --ytdl=no --idle=no --force-window=immediate
        --msg-level=all=warn [--start=<seconds>] -- <video URL>
```

- `<session>` is a random UUID, so a pipe name is never reused (a stale mpv can't clash).
- `--ytdl=no` always: otherwise mpv's ytdl hook retries a failed URL through yt-dlp with a browser User-Agent, which
  hides header bugs and leaks requests (Phase 0 S4).
- A `"` in the URL is sent as `%22` (Java doesn't escape it on Windows); a URL with a line break isn't played.
- mpv's output goes to `<data dir>/logs/mpv.log` (the last session's). mpv gets the backend's environment without
  `MADOYOMI_TOKEN`.

## The session's include file

Everything that comes from the source is written to `<data dir>/cache/player/<session>.conf`, which mpv reads with
`--include`, one option per line in mpv's fixed-length quoting, `name=%<length in UTF-8 bytes>%value`, so every value
arrives verbatim (quotes, commas, semicolons, non-ASCII). The file is deleted when mpv exits. Its options:

| Source data | mpv option |
| --- | --- |
| the title, `<anime> - <episode>` | `force-media-title` |
| header `User-Agent` | `user-agent` |
| header `Referer` | `referrer` |
| every other header | one `http-header-fields-append=Name: value` each (never the comma-joined `http-header-fields`, which splits a value at `,`; Phase 0 S4) |
| `Video.mpvArgs` (lib 16) | `<name>=<value>`, except the reserved options below |

The external subtitle and audio tracks are not in this file: they are added over IPC once the file is open, so they
get the source's label as their title (see below).

Left out (and logged as a warning): a header with an invalid name or a line break in its value, a track without a
URL, and an `mpvArgs` option that would take control away from Madoyomi: the IPC options, `config`, `config-dir`,
`include`, `profile`, `ytdl*`, `script*`, `load-scripts`, `input-conf`/`input-file`/`input-commands`, `log-file`,
encoding outputs (`o`, `of`, `oac`, `ovc` and their options), `stream-record`, `record-file`, `screenshot-*`,
`watch-later-*`, `save-position-on-quit`, `idle`, `playlist`, `start` and `force-media-title`, with any list suffix
(`-append`, `-add`, …).

Why a file rather than arguments: Java's `ProcessBuilder` on Windows doesn't escape a `"` inside an argument, so a
header such as `sec-ch-ua: "Chromium";v="130"` would be cut, and a crafted value could add options. Other processes
can also read a command line, and headers may carry cookies.

## The process

- One session at a time: `playEpisode` stops the running mpv first (`stopPlayback` stops it on its own).
- mpv is a child of the JVM, so it is in the Tauri shell's Job Object: when the app dies, mpv dies. A normal backend
  exit stops it from a shutdown hook.
- Each session writes `<data dir>/cache/player/<session>.session` (pid, executable, start time). When the backend
  starts, it ends a process that still matches one of these records (a backend that crashed left it playing), and
  clears the directory. The pid, the executable and the start time must all match, so a reused pid is never hit.

## IPC (Stage 2)

The pipe is `\\.\pipe\madoyomi-mpv-<session>` (a Unix socket `/tmp/madoyomi-mpv-<session>.sock` elsewhere), mpv's
JSON IPC: one JSON object per line, UTF-8, in both directions. The client is `MpvIpcClient`; the session logic is
`PlaybackSessions` and `PlaybackTracker`.

**Connecting.** mpv creates the pipe a moment after it starts (about 1 s for a local start, longer while an HLS stream
opens). The backend retries every 100 ms for up to 60 s, and stops trying when mpv exits. The pipe is opened with
overlapped I/O (`AsynchronousFileChannel`): with a synchronous handle a pending read would block every write. mpv
accepts several clients, so a script can read properties while the backend is connected.

**Messages.**
- A command is `{"command": [...], "request_id": n}` (ids from 1, per connection), plus `"async": true` for
  `sub-add`/`audio-add`. Its reply is `{"request_id": n, "error": "success", "data": …}`; any other `error` fails it.
- Everything else is an event: `{"event": "property-change", "id": <ours>, "name": …, "data": …}`, `file-loaded`,
  `seek`, `playback-restart`, `end-file` (`reason`: `eof`, `quit`, `error` with `file_error`, `stop`), …
- When mpv exits, the pipe closes: the client closes, and commands still waiting fail.

**Observed** (`observe_property`, ids 1–5; mpv sends the current value at once, then each change):

| id | Property | Used for |
| --- | --- | --- |
| 1 | `time-pos` | the position (saved, progress events, the watched threshold) |
| 2 | `duration` | the threshold and the saved duration (`null`/0 while unknown: no threshold) |
| 3 | `pause` | a save at once on pause; progress events on pause and resume |
| 4 | `eof-reached` | the end of the file (with `end-file` `eof`; `keep-open=no` makes mpv exit right after) |
| 5 | `track-list` | a non-empty list means the file is open already (the pipe connected after `file-loaded`) |

**Commands sent.**
- `observe_property` (above), right after connecting.
- `sub-add <url> auto <title>` and `audio-add <url> auto <title>` (async), once the file is open, for the video's
  external tracks: the title is the source's label (`Track.lang`, e.g. "English"), so mpv's menus show it rather than
  the URL's file name. None is selected as it is added. mpv numbers the tracks per type in the order they finish
  loading.
- Once they have all answered (at most 30 s), `get_property track-list`, then `set_property sid <id|"no">` and
  `set_property aid <id>`: the tracks the player settings choose (Stage 5, below). Without external tracks this only
  happens when a language or "subtitles off" is set.
- `quit` to stop a session (`stopPlayback`, a new `playEpisode`, the backend's shutdown). mpv then exits with code 0.
  Only if it is still running 3 s later (or the pipe never connected) is the process killed (TerminateProcess, code 1).

**Progress.** It goes into the episode's `EPISODE.last_second_seen` and `total_seconds`, in **milliseconds** despite
the names, as the Aniyomi app stores them (its player writes `position * 1000`), so backups and history agree. It is
written:
- every 5 s of playback (not while paused), starting with the first position;
- at once on pause, after a seek (the first position once playback restarts), when the watched threshold is reached,
  and at `end-file`;
- when the session ends (mpv exited, or the backend stopped it), with the last position mpv reported.

A write with the same values as the last one is skipped, and a duration of 0 never replaces a known one. On the
backend's own shutdown nothing is written (the database may be closing too): the last save is at most 5 s old.

**Watched.** The first time `time-pos / duration` reaches the threshold (the player settings' "Mark as watched at",
50–95 %, default 85 %; read at every position, so a change applies to the session that plays) in a session, `EPISODE.watched` is set if it isn't already, and a WATCHED event goes out. It is tried once per session: an
episode the user unmarks while it plays isn't marked again by that session.

**Events** (GraphQL `playbackEvents(episodeId)`, nothing replayed):

| Event | When |
| --- | --- |
| `STARTED` | the pipe is connected |
| `PROGRESS` | the position moved (at most once a second), or mpv paused, resumed or seeked |
| `WATCHED` | the episode was marked watched (once per session) |
| `ENDED` | the end of the file (`reason: eof`), or the user quit past the threshold (`reason: quit`); `nextEpisodeId` is the next episode in the source's order (`source_order` + 1 and up), if any |
| `RETRYING` | mpv couldn't open the video and the backend fetches the episode's videos again (Stage 4, below): `message`, `httpStatus`, `failedVideo`. The session's `CLOSED` follows once the retry is decided |
| `ERROR` | mpv ended early: `end-file` with `error` (`message`: "mpv could not play the video: <file_error>", plus " (HTTP <status>)" when mpv's log has one), or a non-zero exit code. `retried` once the videos were fetched again; `nextVideo` is the video to offer instead |
| `CLOSED` | the session is over (always its last event), with its final state |

A stop by Madoyomi gives no ENDED or ERROR, only CLOSED. Each event carries the session with `positionSeconds`,
`durationSeconds` and `paused`.

**Resuming (Stage 3).** The UI's video picker offers "Resume from mm:ss" when the stored position is at least 1 % and
below the watched threshold of the stored duration, and plays with `startSeconds = lastSecondSeen / 1000` (the
`--start` of the command line). A watched episode starts at 0. Marking an episode unwatched (`updateEpisodes`) sets
`last_second_seen` back to 0.

## When the video doesn't play (Stage 4)

A session that ends with `ERROR`'s conditions (mpv's `end-file` with `reason: error`, or a non-zero exit that wasn't
Madoyomi's) is looked at once more before it is reported:

- **The HTTP status** is read from mpv's log (`<data dir>/logs/mpv.log`, the session's own output): the last
  `HTTP error NNN` line, which FFmpeg's http protocol writes at warn level (`[ffmpeg] https: HTTP error 403
  Forbidden`). mpv's `end-file` only says `file_error: "loading failed"`, so the log is the only place with the status.
- **Early** means mpv never got the video playing in that session: it never reported a position (`time-pos`; a URL
  refused at once, a crash at start, an exit before the pipe opened), or it ended the file with `file_error` "no audio
  or video data played". mpv reports `time-pos` (the start position) as soon as the file is open, before any frame
  plays, so an HLS stream whose playlist opens but whose segments all fail has a position and still never played
  (found live in the Stage 6 exit run).
- **An `eof` that isn't the end.** When a URL stops answering while it plays (it expired, and the next range request
  gets 403), mpv logs the HTTP error and then ends the file as `eof`, with exit code 0 (measured live with AnimeGG's
  MP4). So an `eof` more than 10 s before a known duration, with an HTTP error in the log, counts as a failure ("mpv
  stopped reading the video at mm:ss of mm:ss"), not as `ENDED`. Without an HTTP error it stays mpv's end of the file.
- **Retried**, once, when the session was a play (not already a retry) and it failed early, or its log has a 401, 403,
  404 or 410 (a URL that expired while playing): the backend sends `RETRYING`, fetches the episode's videos again
  (`fetchEpisodeVideos`, the source asked anew), finds **the same video** (the hoster by name, the video by title, else
  by resolution; no other video stands in), resolves it, and starts a new session from the failed one's last position
  (else where it started). Then the failed session's `CLOSED`, and the new session's own events.
- **Not retried**, or the retry didn't help (the source failed, the video isn't there any more, mpv couldn't start, or
  the new session failed too): `ERROR` with `retried`, `httpStatus` and `nextVideo`, the next video in the source's
  order after the failed one (the READY hosters in order, each one's videos in order, wrapping around; never the failed
  one; null when there is no other), then `CLOSED`. The app reopens its video picker with that video preselected.
- A new `playEpisode` or `stopPlayback` while the videos are fetched again cancels the retry: the failed session's
  `CLOSED` goes out at once, and nothing plays.

## Which tracks play (Stage 5)

The player settings name a subtitle and an audio language (or "Off" for subtitles), from a table of 21 languages
(`PlayerLanguages`: English and native names, ISO 639-1 and 639-2 codes). A track matches by its `title` (the source's
label for an external track) or its `lang` (mpv's code for the file's own): one of its words is a name or a three-letter
code ("English (eng)", "English [CC]", "Português (Brasil)", "jpn"), or the whole label is a code with an optional
region ("en", "en-US", "pt_BR").

| | A language that matches | No language, or none matches |
| --- | --- | --- |
| subtitles | the first match: the file's own tracks first, then the source's in the source's order; a forced track only if nothing else matches | the source's first subtitle track (Stage 2's rule), else mpv's own choice; "Off" is `sid=no` |
| audio | the first match, in the same order | the file's own audio as mpv chose it, else the source's first audio track (an HLS stream without audio of its own) |

The backend logs what it set ("subtitles track 2, audio track 3"). A track that fails to load is logged and left out of
the choice.
