# Player contract: how the backend drives mpv

Madoyomi plays episodes in **mpv, in its own window**, and the **backend owns the process** (Phase 4 §9). The UI asks
the backend to play (`playEpisode`) and the backend does the rest. This file is the contract between the backend
(`server/core/src/main/kotlin/madoyomi/core/player/`) and mpv. Stage 2 adds the IPC client and the observed properties.

## Finding mpv and its config

| What | Where from (first match) |
| --- | --- |
| `mpv.exe` | the player settings' override (Stage 5) → `MADOYOMI_MPV` → `mpv.exe` on `PATH` |
| config dir (`--config-dir`) | `MADOYOMI_PLAYER_DIR` if it is a directory → `<data dir>/player` (created empty) |

The Tauri shell sets `MADOYOMI_MPV` to the repo's `tools/bin/mpv/mpv.exe` in dev and to the bundled
`resources/mpv/mpv.exe` in release (Phase 7), when the file is there; otherwise it sets nothing and the backend uses
`PATH`. It sets `MADOYOMI_PLAYER_DIR` to this directory (`player/`) in dev and to `resources/player` in release. A
missing mpv is the error `PLAYER_NOT_FOUND`; a launch that fails is `PLAYER_FAILED`.

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
| each subtitle track URL | `sub-files-append` |
| each audio track URL | `audio-files-append` |
| `Video.mpvArgs` (lib 16) | `<name>=<value>`, except the reserved options below |

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

The pipe is `\\.\pipe\madoyomi-mpv-<session>`, mpv's JSON IPC: one JSON object per line, commands as
`{"command": [...], "request_id": n}`. Stage 2 adds the client, `observe_property` on `time-pos`, `duration`, `pause`,
`eof-reached` and `track-list`, and a graceful `quit` before the process is killed.
