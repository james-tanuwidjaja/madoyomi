Start Phase 4 (docs/phases/phase-4-playback.md), Stage 4: next episode and errors (steps 8, 9, 11 and 15). The next-episode toast with a countdown and auto-play; on a 403/404 or an early exit, the videos fetched again once, then the next video offered; and the proxy fallback for headers mpv can't send, decided by measurement.

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it. §8 has the stage plan (Stage 3 *Done*, Stage 4 next). §9 has the decisions so far: Stage 3's says "Stage 4's auto-play of the next episode may play the remembered choice without the dialog". §10 "Stage 3" has the baseline, the live `pnpm tauri dev` table and what wasn't checked;
- player/IPC.md (the events: ENDED with `nextEpisodeId` and `reason` eof/quit, ERROR, CLOSED, and "Resuming (Stage 3)");
- server/UPSTREAM.md, "Playback (Phase 4)";
- README.md (the status box, the Roadmap row for Phase 4, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev) and app/README.md.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-11, Stage 3 done on feat/phase-4-picker, which I commit and merge before this stage):
- server (api, `madoyomi.core.player`):
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})`, `stopPlayback`, `playbackSession`, `fetchEpisodeVideos`, `fetchHosterVideos`, `updateEpisode(s)`;
  - `PlaybackSessions` (one mpv at a time, resolved right before the launch), `PlaybackTracker` (saves every 5 s and on pause, seek and quit; the watched mark once at 85 %; ENDED with the next episode in the source's order, `reason` eof or quit past the threshold; ERROR when mpv ends early with `end-file` error or a non-zero exit; CLOSED always last);
  - the subscription `playbackEvents` (STARTED, PROGRESS, WATCHED, ENDED, ERROR, CLOSED).
- app:
  - `components/VideoPicker.vue`: Play always opens it, with the remembered choice per source (`localStorage` `madoyomi.videoChoices`, matched by hoster name, then the video's title, else its resolution) preselected and focused; the resume footer between 1 % and 85 % (`utils/progress.ts`, `WATCHED_THRESHOLD` 0.85);
  - `stores/playback.ts`: the live session from `playbackEvents` (through `api/realtime.ts`). ENDED and ERROR are received but nothing in the UI acts on them yet;
  - `EntryItemList` / `EpisodeList`: live rows, the Watched badge at once, selection and bulk marks;
  - `utils/videos.ts` (`matchChoice`, `initialPick`, `videoFacts`).
- Live data in tools/dev-data/agent/live-data (AnimeGG, One Piece, anime id 61, 1167 episodes; episode id = source order for the first ones): episodes 1 and 2 watched; 3–7 (ids 3–7) unwatched, position 0. The `pnpm tauri dev` webview remembers `[DUBBED] AnimeGG:360p` as AnimeGG's last choice.

Baseline (the end of Stage 3; rerun at the start as a check, and the counts must not drop):
- server: `.\gradlew.bat build` green with 717 tests, 0 failures: core 640 (8 skipped), AndroidCompat 7, ext-host 8, api 62. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml;
- tools/smoke-api.ps1 -NoBuild: 24 steps pass;
- app: `pnpm test` gives 37 files, 307 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
- the live spec (tools/dev-data/agent/seed-live.ps1): 9/9 with 38 documents validating;
- app/src-tauri: `cargo test` 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh). Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done. Scratch helpers there (gitignored): app-start.ps1 (starts `pnpm tauri dev` on live-data, hidden, output in tauri-dev.log, and waits for "healthy after"), app-stop.ps1 (closes the window, then lists what's left), ipclib.ps1 (`MpvPipe`, `WaitMpvPipe`, `Ipc`, `Props`), stored.ps1 (a server of its own on live-data to read episodes; only while the app is closed). node_modules has only Windows binaries, so pnpm doesn't run in the VM.
- PowerShell 5.1 wraps a native command's stderr as errors: run pnpm through `cmd /c "pnpm ... 2>&1"` and read `$LASTEXITCODE`.
- Gradle: run `ktlintFormat` and `build` as two separate invocations (together they fail on an implicit task dependency, `:core:generateJte`). Start it through `Start-Process` with `-PassThru` and `WaitForExit()` and an absolute `JAVA_HOME` (D:\Projects\madoyomi\tools\bin\jdk-21): a Gradle daemon keeps a redirected pipe open, so `cmd /c gradlew ... > file` inside a runner job never returns. Don't rebuild while a backend runs from the jar.
- In PowerShell scripts, `R` is an alias (Invoke-History), so don't name a function that. `$Args` is automatic, `Sequence` is reserved, `$pid` is read-only, and names are case-insensitive (a `[string]$Pick` parameter constrains `$pick`). Keep .ps1 files ASCII.
- The LongString scalar takes a string literal (`lastSecondSeen: "60000"`), not an int.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR='D:\Projects\madoyomi\tools\dev-data\agent\live-data'`; wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log. Close it with `(Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow()` (pnpm then prints `[ELIFECYCLE] ... exit code -1`; the shell's "backend stopped (exit code 0)" is what counts). Kill only the pid from sidecar.log: other java processes run (VS Code's Gradle servers). One server per data dir: H2 locks it.
- Computer use: resolve the app by its path, D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe, while it runs (the name "Madoyomi" doesn't resolve), and mpv as "MPV Player". It can't type into terminals. Take a screenshot before each click: the Claude window may cover part of the app, and the bulk bar shifts the rows down when it appears. Release the lock at the end.
- mpv: C:\Program Files\MPV Player\mpv.exe, on PATH. The backend talks to it over `\\.\pipe\madoyomi-mpv-<session id>`, and mpv accepts several clients (`MpvPipe` in ipclib.ps1 finds it). Mute mpv in automated steps. `quit` over IPC exits with code 0.
- AnimeGG's Play can take 10–20 s (the videos are fetched first). Its one hoster is `no_hoster_list`, with 5 videos (1080p, 720p, 480p and 360p SUBBED, and 360p DUBBED), so "the next video" has candidates. AnimeGG's host answers 500 without the Referer.
- The header proxy (spikes/mpv-play/header-proxy.mjs, `node header-proxy.mjs [port]`) refuses requests without its Referer and User-Agent; it has no expiring mode yet. An expiring URL (200, then 403 after N seconds or N requests) needs one, and pointing a session at it needs a test-only or scratch way in: keep any such hook out of the release code path, or behind a test.
- The token must stay out of logs, URLs and mpv's command line. At the end, check sidecar.log, madoyomi.log, mpv.log and the dev output for `token=`.

Tasks:
0. Branch: `git switch main`, `git pull`, `git switch -c feat/phase-4-next`. If feat/phase-4-picker isn't merged into main yet, stop and tell me.
1. Rerun the baseline (the server only if you'll change it before the first build; otherwise its counts are the start). Record it in §10 "Stage 4".
2. Plan the stage briefly, then implement:
   a. **Next episode (steps 8, 15):** on ENDED with a `nextEpisodeId`, the app shows a toast ("Next: Episode N" with a countdown, Play now and Cancel). When the countdown ends and auto-play is on, it plays the next episode with the remembered choice for the source, without the picker (§9 Stage 3), from the start or from its resume point (decide, and record why). Cancel, a new Play, or leaving the details page stops it. Auto-play: a setting with a default; until Stage 5's player settings section, keep it a device preference like the others (Phase 3 §9). The countdown length is a default you choose (record it).
   b. **Retry and fallback (steps 9, 11):** when mpv fails to open the video (ERROR from `end-file` with an error, or an early non-zero exit) on a 403/404 or an expired URL, the backend fetches the episode's videos again once, finds the same video (hoster name + title, as the picker matches), and relaunches at the same position. If that fails too, the app offers the next video in the source's order (the picker reopened with it preselected and the error shown). Say in §9 what counts as "early" and how the HTTP status is read (mpv's `end-file` `file_error`, its log, or both).
   c. **Proxy fallback (step 11):** measure whether any header a source sends can't reach mpv (the include file sends every header verbatim since Stage 1). If none, record the measurement and defer the proxy (PLAN.md §6 risk "Video URLs require headers/cookies").
   d. Tests: unit tests for the retry and for picking the next video (the server, with `FakeMpvServer`), and component and store tests for the toast, the countdown, auto-play and the fallback (the app).
3. Live, through the API and in `pnpm tauri dev` (computer use):
   - an episode played to the end (a seek near the end over IPC) shows the toast, and the countdown starts the next episode with the remembered choice; Cancel stops it;
   - an expiring URL (the header proxy with an expiring mode) fails once, is fetched again, and plays; when the refetch fails too, the next video is offered;
   - check that the §6 criterion "The next episode starts from the end-of-episode prompt" passes live;
   - afterwards, put live-data back roughly as it was (episodes 1 and 2 watched, the rest unwatched with position 0);
   - check for `token=` in the logs and the dev output.
4. Phase doc:
   - §5: tick "Next-episode toast / auto-play setting", "403/expired retry + fallback to next video" and "Proxy fallback for headers mpv can't send" (or record the deferral) only if verified;
   - §6: tick "The next episode starts from the end-of-episode prompt" only if it passed live;
   - §8: Stage 4 *Done* (2026-…), with a summary; §9 "Stage 4" decisions; §10 "Stage 4" findings (the baseline, counts after, the live table, what wasn't checked and why).
5. server/UPSTREAM.md for any server change (every upstream edit gets a `// Madoyomi:` comment); player/IPC.md for any event or behaviour change.
6. docs/PLAN.md:
   - Phase 4 status: "Stage 4 of 6 done", with what Stage 4 added;
   - tick "Next-episode flow" and "Expired-URL retry / fallback" only if every phase-doc item behind them is verified;
   - update §8 "First tasks", §6 risks (the headers risk, with the proxy measurement) and §7 (nothing new expected).
7. README.md, matching the phase doc and PLAN.md:
   - the status box (Stage 4 of 6, the next episode, the retry);
   - the "Play an episode" bullet;
   - the API bullets, for any new or changed API;
   - the Roadmap row for Phase 4;
   - "Decided so far in Phase 4": this stage's decisions;
   - the commands tables and the NOTE on pnpm tauri dev, only if they change.
8. At the end, write the ready-to-paste prompt for Stage 5 (player settings: steps 9 and 16; the watched threshold, preferred quality, server and subtitle/audio language with the sorting, and the mpv path override, in a Settings section) into NEXT-PROMPT.md (overwrite it), and repeat it in your final message. Use this same format: current state, baseline test counts, gotchas, the tasks (task 0 is the branch switch to feat/phase-4-settings, off an up-to-date main; include the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 307 + 9 skipped, plus the new tests). If the server changed: `.\gradlew.bat build` ≥ 717 green, smoke 24/24, and the live spec 9/9 with ≥ 38 documents. If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The live checks are recorded in §10. The §5 and §6 ticks are only items that passed, and §8 shows Stage 4 *Done*.
- docs/PLAN.md: Phase 4's milestones are ticked if and only if their phase-doc items are verified, and §4/§8 show "Stage 4 of 6 done".
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md holds the Stage 5 prompt.
- `git status`: on feat/phase-4-next, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv binaries, video files;
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
