Start Phase 4 (docs/phases/phase-4-playback.md), Stage 2: IPC and progress (steps 4-8, 19). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it. §8 has the agreed stage plan; Stage 1 is *Done*. §9 has the decisions (mpv in its own window, the backend owns mpv, where mpv comes from, the include file, progress in the existing EPISODE columns), and §10 has Stage 1's findings;
- player/IPC.md: the contract between the backend and mpv. Stage 2 fills in its "IPC (Stage 2)" section;
- server/UPSTREAM.md: "Playback (Phase 4)", "Startup contract", "Anime sources" and "Database migrations";
- the Phase 0 doc's S4 findings (the named pipe; `time-pos` read over JSON IPC);
- README.md (the status box, the Roadmap row for Phase 4, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev) and app/README.md.
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-3 done; Phase 4 Stage 1 of 6 done, 2026-11-07, on branch feat/phase-4-launcher):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar. Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md. Stage 1 changed no upstream file.
- The player (Stage 1, `madoyomi.core.player` in core; server/UPSTREAM.md "Playback (Phase 4)"):
  - `MpvLocator`: an explicit path (Stage 5's setting), the system property `madoyomi.player.mpv`, env `MADOYOMI_MPV`, then `mpv.exe` on PATH; otherwise `PLAYER_NOT_FOUND` (503). `PlayerDirs`: mpv's config dir is `MADOYOMI_PLAYER_DIR`, else `<data dir>/player`;
  - `MpvCommand`: only Madoyomi's values on the command line (`--config-dir`, `--include=<data dir>/cache/player/<id>.conf`, `--input-ipc-server=\\.\pipe\madoyomi-mpv-<id>`, `--ytdl=no`, `--idle=no`, `--force-window=immediate`, `--msg-level=all=warn`, `--start`, `--` URL). The source's values (headers in S4's forms, the title, subtitle and audio URLs, `mpvArgs` minus the reserved ones) are in the include file, as `name=%<UTF-8 bytes>%value`;
  - `PlaybackSessions`: one session at a time (`play` resolves the video through `AnimeVideos.resolveVideo`, stops the running mpv and starts the new one; `stop`; `current`). A session is RUNNING until mpv exits (EXITED, with its code) or is stopped (STOPPED). Stopping is `Process.destroy()` today; Stage 2 sends `quit` over IPC first. A shutdown hook stops mpv. `<data dir>/cache/player/<id>.session` (pid, executable, start time) lets `reapOrphans()` (called from Main) end an mpv that a crashed backend left;
  - `PlaybackSessions.executable` is public so tests can swap in a fake mpv (`api/src/test/java/madoyomi/api/FakeMpv.java`, Java without dependencies, started with `-cp` on its class dir).
- The API: `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})` → `{session}`, `stopPlayback(input: {})` → `{session}`, query `playbackSession`. `PlaybackSessionType` is id, episodeId, animeId, title, videoTitle, state (RUNNING/EXITED/STOPPED), startedAt, endedAt, exitCode. There are no playback events yet, and nothing is saved.
- Progress goes into the existing `EPISODE.watched`, `last_second_seen` and `total_seconds` (M0070; EpisodeType already has isWatched, lastSecondSeen and totalSeconds as LongString). The GraphQL field says seconds. Check which unit the Aniyomi app stores there (it may be milliseconds despite the name), since backups and Phase 6 history should agree, and record the unit you pick in §9. No migration is needed unless you add a column (then it is M0072, under UPSTREAM.md "Database migrations").
- The shell (app/src-tauri/src/sidecar.rs) passes `MADOYOMI_MPV` (set, or tools/bin/mpv/mpv.exe if present, or resources/mpv in release) and `MADOYOMI_PLAYER_DIR` (set, or the repo's player/, or resources/player) to the backend, and logs a `player:` line in sidecar.log. mpv is the JVM's child, so it is in the shell's Job Object: closing or killing the app ends mpv (checked live).
- The UI: each episode row has Play (components/EpisodeList.vue, composables/usePlayback.ts, utils/videos.ts). It takes the first READY hoster's preferred video and shows "Starting…" until mpv is up, with errors above the rows. api/operations/playback.ts has EPISODE_VIDEOS_MUTATION, PLAY_EPISODE_MUTATION, STOP_PLAYBACK_MUTATION and PLAYBACK_SESSION_QUERY.
- mpv is installed at C:\Program Files\MPV Player\mpv.exe and is on PATH; tools/bin/mpv/ doesn't exist, so dev uses PATH.
- PLAN.md: the Phase 4 milestone "mpv launcher with headers / subs / audio" is ticked; "Named-pipe IPC + progress saving" is the one Stage 2 works towards. §7's mpv window question is closed.

Baseline (after Phase 4 Stage 1; rerun all of it at the start and record it as this stage's baseline):
- server: `.\gradlew.bat build` is green with 688 tests, 0 failures: core 620 (8 or 9 skipped: the count varies between runs), AndroidCompat 7, ext-host 8, api 53. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Server changes need tests, a rebuild and an UPSTREAM.md entry.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass.
- app: `pnpm test` gives 34 files, 266 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 9/9 against a seeded server, with 35 documents validating.
- app/src-tauri: `cargo test` 17 tests; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell turns out to be a Linux VM (no gradlew.bat, PowerShell, pnpm, cargo or mpv), there is a job runner in tools/dev-data/agent/ (gitignored). Ask me to double-click start-runner.cmd there. It runs each queue\*.ps1 once, hidden, and writes queue\<job>.log (UTF-8) and queue\<job>.done (the exit code). Write job scripts there; inside functions log with Write-Host (a function's Write-Output becomes its return value). Computer use can't type into terminals or Explorer, but it can drive the Madoyomi window (target\debug\madoyomi.exe) and mpv (C:\Program Files\MPV Player\mpv.exe). The Claude desktop window sometimes covers the right side of the app: take a screenshot before each click.
- Named pipes from Java:
  - mpv on Windows only offers a named pipe for JSON IPC (`--input-ipc-client` is Unix-only);
  - a synchronous handle (`RandomAccessFile` on `\\.\pipe\...`) serializes I/O, so a blocked read on one thread also blocks writes from another. Prefer `AsynchronousFileChannel` (overlapped I/O) or a pattern that never writes while a read is pending, and prove it with the fake-pipe test (step 19);
  - the pipe appears a moment after mpv starts: connect with retries (Stage 1 saw it within about 1 s for a local start, longer while an HLS stream loads);
  - mpv accepts several clients on the same pipe.
- Stage 1's live script (tools/dev-data/agent/liveplay.ps1, scratch) reads mpv properties over the pipe from PowerShell with System.IO.Pipes.NamedPipeClientStream. Reuse it to check stored progress live.
- Live data: tools/dev-data/agent/live-data is a seeded data dir (yuzono and Keiyoushi, with AnimeGG, MangaPill, KickAssAnime and MangaDex installed). tools/dev-data/agent/seed-live.ps1 seeds a dir and runs the live spec (`-DataDir`, `-Spec`). One server per data dir: H2 locks it.
- Videos: AnimeGG gives MP4s (its host answers 500 without the Referer; the Play takes 10-20 s). KickAssAnime gives HLS with 7 headers, 8 subtitles and 2 audio tracks. URLs expire, so resolve right before launching (playEpisode already does).
- mpv: `keep-open=no` is in player/mpv.conf, so mpv exits at the end of the file. Watch for `eof-reached` / `end-file` before the process exits. `q` quits with exit code 0, while TerminateProcess gives 1. Mute mpv over IPC in automated checks (`set_property mute true`).
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <log> 2>&1"` (about 4-6 min).
  - Run `.\gradlew.bat ktlintFormat` first. The test source set's ktlint check can run before the format and fail on chain wrapping.
  - Backtick test names can't contain `:` (or `.`, `/`, `;`, `[`, `<`, `>`, `\`).
  - Kotlin `internal` isn't visible from api's tests (another module).
  - A class property named `java` shadows `java.util...` in fully qualified names.
  - Don't rebuild while a backend runs from that jar (Windows locks it): close the app first.
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
- PowerShell: from the PowerShell tool stdin is the null device, so start servers with Start-MadoyomiServer (tools/lib/MadoyomiServer.ps1), which sets $script:base, $script:token and $script:proc. Variables are case-insensitive (`$pid` is read-only). PS 5.1 has no `??`. Keep .ps1 files ASCII.
- Live app checks: run `pnpm tauri dev` with MADOYOMI_DATA_DIR set, wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log, and close it with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow(). Kill only the pid from sidecar.log: other java processes are running.
- Reka in jsdom, TypeScript 6 without @types, ESLint's `sort` heuristic, `pnpm format` rewriting files, and app/.vitest/: as in earlier stages (the Phase 3 doc's §10).
- The token must stay out of logs, URLs and mpv's command line and environment. At the end, check sidecar.log, madoyomi.log, mpv.log and the dev output for `token=`.
- Python and gh are not installed on Windows.

Tasks (Phase 4 Stage 2):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-4-ipc`. Never edit on main. If feat/phase-4-launcher isn't merged yet, stop and tell me.
1. Baseline: rerun everything in "Baseline" (smoke and the live spec included) and record it.
2. Plan the stage briefly (the §8 entry for Stage 2), then implement it:
   - `MpvIpcClient` (core, `madoyomi.core.player`):
     - connect to the session's pipe with retries;
     - commands with `request_id` and their replies;
     - line-delimited JSON events;
     - `observe_property` for `time-pos`, `duration`, `pause`, `eof-reached` and `track-list`;
     - a clean close when mpv exits.
   - `PlaybackSessions`: a graceful `quit` over IPC before the kill. Give external subtitle tracks their language as their title (`sub-add` with a title, or track properties), if that is cheap.
   - Progress: saved every 5 s while playing and at once on pause, seek and quit, into `EPISODE.last_second_seen`/`total_seconds` in the unit you record in §9. The episode is marked `watched` once when time-pos/duration reaches the threshold (85% default; the setting comes in Stage 5), and not marked again after the user unmarks it in the same session.
   - Events: a GraphQL subscription (e.g. `playbackEvents`) with progress (throttled), watched, ended (end of file, with the next episode's id if there is one) and error (early exit). Nothing is replayed, so the UI subscribes first.
   - Tests: a fake named-pipe server for the IPC client (step 19); session tests over a fake mpv that speaks the protocol (extend FakeMpv.java, or add a small pipe-serving fake); progress and watched-threshold tests on a scratch H2.
   - Update player/IPC.md: what is observed, the commands sent, and when progress is written.
   - Live check through the API: play AnimeGG from 60 s, let it run, quit mpv halfway, and read the stored position and duration back (`episodes` query). Then let a short seek to the end mark the episode watched, and show the event sequence.
   - UI: only what the stage needs to show the result. If the episode list's watched badge reads `isWatched` already, check that it shows after a refetch. The picker, resume prompt and live progress bars are Stage 3.
3. Phase doc: §5 ticks (only verified items), §8 (Stage 2 *Done* with a summary), §9 decisions, and §10 findings, including what wasn't checked live and why.
4. docs/PLAN.md: Phase 4 status "Stage 2 of 6 done". Tick "Named-pipe IPC + progress saving" only if every phase-doc item behind it is verified. Update §8 "First tasks", the §6 risks and §7.
5. README.md: the status box, the Roadmap row for Phase 4, the commands tables for any new or changed command, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev if it changes. Make it match the phase doc and PLAN.md. Update app/README.md if the structure or routes change, and server/UPSTREAM.md for every server change (new tables as migrations).
6. At the end, write the ready-to-paste prompt for the next stage (Stage 3: picker, resume, watched state in the UI) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 266 tests (the live spec skipped);
  - the live spec passes 9/9 (or more) against a seeded server, with every document validating;
  - `cargo build`, `cargo test` (at least 17) and `cargo clippy --all-targets` pass.
- Server: `.\gradlew.bat build` is green (at least 688 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists every change (new tables as migrations).
- The live check of stored progress and the watched mark is recorded in §10, with the event sequence.
- The Phase 4 doc's §5 ticks are only verified items, and §8 shows Stage 2 *Done*.
- docs/PLAN.md: Phase 4's milestones are ticked if and only if their phase-doc items are verified, and §4/§8 show "Stage 2 of 6 done".
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on feat/phase-4-ipc, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv binaries or video files;
  - hs_err logs, heap dumps, screenshots;
  - node_modules, target/, dist/, app/.vitest/, .env.local;
  - tools/compat-test/out, tools/dev-data, spikes/mpv-play/out, or anything mpv wrote into player/.

Standing rules:
- Start every stage by switching to a new feature branch off an up-to-date main (git switch main, git pull, git switch -c feat/phase-N-<topic>) before any edit. Never edit on main.
- Work one stage at a time.
- Never commit (no git commit/merge/rebase/cherry-pick/revert). Never commit or stage binaries, APKs, jars, .pb files or heap dumps.
- Ask me only about decisions that are genuinely mine.
- Every stage updates, together with the phase doc and, for server changes, server/UPSTREAM.md:
  - README.md's status (the status box, the Roadmap row, the changed tables, the decisions);
  - docs/PLAN.md's milestones (tick what the phase doc verifies; update the phase status, the risks and the open decisions).
- At the end of the stage, stop and report what changed, the decisions and findings, how you verified it (test counts against the baseline), a one-line commit message and a PR title.
- Then write a ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it at the end of your report. Use this same format: current state, baseline test counts, gotchas, the tasks with the branch switch as task 0 and the README status / PLAN.md tick tasks, verification steps including the README and PLAN.md checks, and these standing rules. Tell that agent to do the same.
- Then wait for me to commit or merge.
