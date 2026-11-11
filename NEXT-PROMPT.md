Start Phase 4 (docs/phases/phase-4-playback.md), Stage 3: picker, resume and watched state in the UI (steps 12-14). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it. §8 has the agreed stage plan; Stages 1 and 2 are *Done*. §9 has the decisions (mpv in its own window, the backend owns mpv, where mpv comes from, the include file; Stage 2: progress in milliseconds, when it is saved, the watched mark once per session at 85 %, ENDED/ERROR rules, `quit` before the kill, tracks over IPC). §10 has both stages' findings, including what wasn't checked live;
- player/IPC.md: the contract between the backend and mpv, with Stage 2's "IPC (Stage 2)" section (what is observed, the commands, when progress is written, the events);
- server/UPSTREAM.md: "Playback (Phase 4)" (with its Stage 2 entry), "Startup contract" and "Database migrations";
- README.md (the status box, the Roadmap row for Phase 4, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev) and app/README.md.
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-3 done; Phase 4 Stage 2 of 6 done, 2026-11-11, on branch feat/phase-4-ipc):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar. Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md. Stage 2's only upstream edit is one test-dependency line in core/build.gradle.kts (JNA).
- The player (`madoyomi.core.player` in core):
  - Stage 1: `MpvLocator`, `PlayerDirs`, `MpvCommand` (only Madoyomi's values on the command line; headers, the title and `mpvArgs` in the per-session include file), `PlaybackSessions` (one session at a time, orphans of a crashed backend ended);
  - Stage 2: `MpvIpcClient` (the named pipe as an `AsynchronousFileChannel`, overlapped I/O; retries, `request_id` replies, events on a reader thread, `observe_property`, closes when mpv closes the pipe), `PlaybackTracker` (saves every 5 s while playing and at once on pause, seek, the threshold and the end; the watched mark once per session at `PlaybackSessions.watchedThreshold` = 0.85; progress events at most 1/s; the outcome), `EpisodeProgress` (writes `EPISODE.last_second_seen`/`total_seconds` in MILLISECONDS, as the Aniyomi app does; sets `watched` only if unset; the next episode by `source_order`);
  - external subtitle/audio tracks are added over IPC (`sub-add`/`audio-add`, async, the source's label as title): the first subtitle selected, the first audio track only when the file has no audio of its own. mpv numbers them in load order;
  - stopping sends `quit` over IPC (exit code 0), and kills only after 3 s or without a pipe.
- The API:
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})` → `{session}`, `stopPlayback(input: {})`, query `playbackSession`;
  - `PlaybackSessionType` also has `positionSeconds`, `durationSeconds` and `paused` (live while it plays);
  - subscription `playbackEvents(episodeId)` → `PlaybackEventType {type (STARTED, PROGRESS, WATCHED, ENDED, ERROR, CLOSED), session, nextEpisodeId, reason, message}`. Nothing is replayed;
  - `EpisodeType.lastSecondSeen`/`totalSeconds` are milliseconds (LongString), despite the names;
  - there is no mutation to mark episodes watched/unwatched yet: upstream's manga side has `updateChapters` (patch `isRead`, `lastPageRead`, ...), and the anime side needs `updateEpisode`/`updateEpisodes` in that shape.
- The UI:
  - each episode row has Play (components/EpisodeList.vue via EntryItemList.vue, composables/usePlayback.ts, utils/videos.ts): the first READY hoster's preferred video, "Starting…" until mpv is up;
  - api/realtime.ts subscribes to `playbackEvents` and invalidates `queryKeys.episodes(animeId)` on WATCHED and CLOSED;
  - the list's "Watched" badge reads `isWatched` (EntryItemList), but there is no progress bar and no resume yet;
  - api/operations/playback.ts has EPISODE_VIDEOS_MUTATION, PLAY_EPISODE_MUTATION, STOP_PLAYBACK_MUTATION, PLAYBACK_SESSION_QUERY and the exported PLAYBACK_SESSION_FIELDS fragment; subscriptions.ts has PLAYBACK_EVENTS_SUBSCRIPTION.
- Not checked live in Stage 2 (Phase 4 §10): the Watched badge appearing in `pnpm tauri dev` without a manual refresh; the backend's own shutdown with mpv playing (now quit over IPC); ENDED with `reason=quit` live.
- mpv is installed at C:\Program Files\MPV Player\mpv.exe and is on PATH; tools/bin/mpv/ doesn't exist, so dev uses PATH.
- PLAN.md: the Phase 4 milestones "mpv launcher with headers / subs / audio" and "Named-pipe IPC + progress saving" are ticked; "Video picker, resume prompt, watched threshold" is the one Stage 3 works towards.

Baseline (after Phase 4 Stage 2; rerun all of it at the start, before any edit, and record it as this stage's baseline):
- server: `.\gradlew.bat build` is green with 711 tests, 0 failures: core 640 (8 or 9 skipped: the count varies between runs), AndroidCompat 7, ext-host 8, api 56. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build (the `<testsuite tests= failures= errors= skipped=>` attributes of each file). The count must not drop. Server changes need tests, a rebuild and an UPSTREAM.md entry.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass.
- app: `pnpm test` gives 34 files, 267 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 9/9 against a seeded server, with 36 documents validating.
- app/src-tauri: `cargo test` 17 tests; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell turns out to be a Linux VM (no gradlew.bat, PowerShell, pnpm, cargo or mpv), there is a job runner in tools/dev-data/agent/ (gitignored). Ask me to double-click start-runner.cmd there. It runs each queue\*.ps1 once, hidden, as soon as it appears (jobs run IN PARALLEL: put dependent steps in one job), and writes queue\<job>.log (UTF-8, often only at the end) and queue\<job>.done (the exit code). Inside functions log with Write-Host (a function's Write-Output becomes its return value). Computer use can't type into terminals or Explorer, but it can drive the Madoyomi window (target\debug\madoyomi.exe) and mpv (C:\Program Files\MPV Player\mpv.exe). The Claude desktop window sometimes covers the right side of the app: take a screenshot before each click.
- **Run Gradle from a job without redirected handles**, or the job hangs: a Gradle daemon the build starts inherits the job's output pipe and keeps it open forever. Use `$p = Start-Process cmd.exe -ArgumentList "/c cd /d D:\Projects\madoyomi\server && .\gradlew.bat <tasks> --console=plain > <log> 2>&1" -WindowStyle Hidden -PassThru; $p.WaitForExit()` (queue\113-gradle.ps1 is an example). Stopping a stuck job's process was refused by the agent's permission policy; I had to end it myself.
- PowerShell pitfalls: `$Args` is automatic (don't name a parameter that), `Sequence` is a reserved word, a script-level typed parameter constrains every variable of that name (names are case-insensitive), `$pid` is read-only, PS 5.1 has no `??`. Keep .ps1 files ASCII.
- Named pipes:
  - the backend is already connected to the session's pipe; mpv accepts several clients, so scripts (and tests) can connect too;
  - tools/dev-data/agent/liveplay2.ps1 (scratch) is Stage 2's live check: it plays through the API, follows `playbackEvents` over the WebSocket (WsOpen/WsRecv from tools/lib/MadoyomiServer.ps1), drives mpv over a second IPC client (mute, seek, quit) and reads the stored values back with the `episodes` query. It picks the first unwatched AnimeGG episode, so each run uses a new one (episodes 1 and 2 of One Piece are now watched in live-data);
  - the tests' fake mpv is core/src/test/java/madoyomi/core/player/FakeMpvServer.java (Java + JNA, test only): in-process for MpvIpcClientTest, and as the mpv process in PlaybackApiTest (`-Dfakempv.duration/speed/mode/loadMillis`). It records its arguments and, at exit, what it was sent to %TEMP%\madoyomi-fake-mpv. The pipe tests are @EnabledOnOs(WINDOWS).
- Progress units: the DB and GraphQL hold milliseconds (`lastSecondSeen` 752043 = 752.043 s); the session's `positionSeconds`/`durationSeconds` and `playEpisode`'s `startSeconds` are seconds. The resume prompt converts.
- Live data: tools/dev-data/agent/live-data is a seeded data dir (yuzono and Keiyoushi, with AnimeGG, MangaPill, KickAssAnime and MangaDex installed). tools/dev-data/agent/seed-live.ps1 seeds a dir and runs the live spec (`-DataDir`, `-Spec`). One server per data dir: H2 locks it.
- Videos: AnimeGG gives MP4s (its host answers 500 without the Referer; a Play takes 10-20 s, mostly fetching the videos). KickAssAnime gives HLS with 7 headers, 8 subtitles and 2 audio tracks, and no audio in the stream itself. URLs expire, so resolve right before launching (playEpisode already does).
- mpv: `keep-open=no` is in player/mpv.conf, so mpv exits at the end of the file. `q` quits with exit code 0, while TerminateProcess gives 1. Mute mpv over IPC in automated checks (`set_property mute true`).
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'`, then gradlew in server/ (see the job note above; about 5-8 min).
  - Run `.\gradlew.bat ktlintFormat` first. It also rewraps long lines in tests, so re-read a file before editing it again.
  - Backtick test names can't contain `:` (or `.`, `/`, `;`, `[`, `<`, `>`, `\`).
  - Kotlin `internal` isn't visible from api's tests (another module).
  - A class property named `java` shadows `java.util...` in fully qualified names.
  - Don't rebuild while a backend runs from that jar (Windows locks it): close the app first.
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - shadowJar prints ~1455 "com/sun/jna/... (2 times)" duplicate lines: that was already so before Stage 2 (two JNA versions on the runtime classpath), not a new problem.
- PowerShell: from the PowerShell tool stdin is the null device, so start servers with Start-MadoyomiServer (tools/lib/MadoyomiServer.ps1), which sets $script:base, $script:token and $script:proc.
- Live app checks: run `pnpm tauri dev` with MADOYOMI_DATA_DIR set, wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log, and close it with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow(). Kill only the pid from sidecar.log: other java processes are running (VS Code's Gradle servers among them).
- Reka in jsdom, TypeScript 6 without @types, ESLint's `sort` heuristic, `pnpm format` rewriting files, and app/.vitest/: as in earlier stages (the Phase 3 doc's §10).
- The token must stay out of logs, URLs and mpv's command line and environment. At the end, check sidecar.log, madoyomi.log, mpv.log and the dev output for `token=`.
- Python and gh are not installed on Windows (the Linux VM has python3, handy for counting test XMLs).

Tasks (Phase 4 Stage 3):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-4-picker`. Never edit on main. If feat/phase-4-ipc isn't merged yet, stop and tell me.
1. Baseline: rerun everything in "Baseline" (smoke and the live spec included) before any edit, and record it.
2. Plan the stage briefly (the §8 entry for Stage 3), then implement it:
   - `VideoPicker` (step 12): Play opens a dialog with the episode's hosters (`fetchEpisodeVideos`: name, state, a lazy hoster's Load via `fetchHosterVideos`, errors), each video's quality/resolution and its subtitle and audio counts; the source's preferred video marked. Remember the last choice per source (hoster name and quality, kept like the other UI preferences of this device) and preselect it. Decide (and record in §9) whether a remembered choice plays at once or still opens the picker.
   - Resume (step 13): when the stored position is between 1 % and the watched threshold of the stored duration, "Resume from mm:ss" / "Start over" before playing; `startSeconds` = lastSecondSeen / 1000. A watched episode starts over without asking (Aniyomi's behaviour; record it).
   - Watched state and progress (step 14): a progress bar or "mm:ss left" per row from lastSecondSeen/totalSeconds, live while it plays (PROGRESS events update the row, without refetching each second), the Watched badge on WATCHED, and bulk "Mark watched" / "Mark unwatched" (selection, or "mark previous as watched"). This needs `updateEpisode`/`updateEpisodes(input: {ids, patch: {isWatched, lastSecondSeen}})` in the shape of upstream's `updateChapters` (api, `AnimeMutation`), with tests; unmarking keeps or clears the position: decide, and record it in §9.
   - Tests: component tests for the picker (lazy hoster, errors, remembered choice), the resume prompt (the 1 %/threshold bounds, milliseconds to seconds) and the live row updates; API tests for the new mutation.
   - Live in `pnpm tauri dev`: pick a non-default video in the picker and see it play; quit halfway, close the app, reopen it, and resume within ±5 s (a §6 exit criterion); seek past 85 % and see the Watched badge appear without a refresh (another §6 criterion, not checked live in Stage 2); bulk mark and unmark.
3. Phase doc: §5 ticks (only verified items), §8 (Stage 3 *Done* with a summary), §9 decisions, and §10 findings, including what wasn't checked live and why. Tick a §6 exit criterion only if it passed live.
4. docs/PLAN.md: Phase 4 status "Stage 3 of 6 done". Tick "Video picker, resume prompt, watched threshold" only if every phase-doc item behind it is verified. Update §8 "First tasks", the §6 risks and §7.
5. README.md: the status box, the Roadmap row for Phase 4, the commands tables for any new or changed command, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev if it changes. Make it match the phase doc and PLAN.md. Update app/README.md for new components, composables or routes, and server/UPSTREAM.md for every server change (new tables as migrations).
6. At the end, write the ready-to-paste prompt for the next stage (Stage 4: next episode and errors) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 267 tests plus the new ones (the live spec skipped);
  - the live spec passes 9/9 (or more) against a seeded server, with every document validating (36 or more);
  - `cargo build`, `cargo test` (at least 17) and `cargo clippy --all-targets` pass.
- Server: `.\gradlew.bat build` is green (at least 711 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists every change (new tables as migrations).
- The live checks in `pnpm tauri dev` (picker, resume within ±5 s after an app restart, the Watched badge without a refresh, bulk marks) are recorded in §10.
- The Phase 4 doc's §5 ticks are only verified items, and §8 shows Stage 3 *Done*.
- docs/PLAN.md: Phase 4's milestones are ticked if and only if their phase-doc items are verified, and §4/§8 show "Stage 3 of 6 done".
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on feat/phase-4-picker, with only text files. None of these:
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
