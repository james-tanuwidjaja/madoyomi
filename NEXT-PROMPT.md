Start Phase 4 (docs/phases/phase-4-playback.md), Stage 5: player settings (steps 9 and 16). A Player section in Settings with the watched threshold, preferred quality, preferred server, preferred subtitle and audio language (with the video sorting that uses them), and the mpv path override, each taking effect live.

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it. §8 has the stage plan (Stage 4 *Done*, Stage 5 next). §9 has the decisions so far: Stage 1's "Where mpv comes from" (the override comes first), Stage 2's watched threshold (85 %, once per session) and external tracks ("Stage 5's language preference will choose between them"), Stage 3's remembered choice per source, and Stage 4's auto-play device preference ("Stage 5 moves it into the player settings section"). §10 "Stage 4" has the baseline, the live tables and what wasn't checked;
- player/IPC.md (the include file, the external tracks over IPC and which is selected, the events, "When the video doesn't play (Stage 4)");
- server/UPSTREAM.md, "Playback (Phase 4)";
- README.md (the status box, the Roadmap row for Phase 4, "Decided so far in Phase 4", and the NOTE on pnpm tauri dev) and app/README.md.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-11, Stage 4 done on feat/phase-4-next, which I commit and merge before this stage):
- server (api, `madoyomi.core.player`):
  - `playEpisode(input: {episodeId, hosterIndex, videoIndex, startSeconds})`, `stopPlayback`, `playbackSession`, `fetchEpisodeVideos`, `fetchHosterVideos`, `updateEpisode(s)`;
  - `MpvLocator.locate(override = null, …)`: the override parameter exists but nothing sets it yet (then `MADOYOMI_MPV`, then PATH);
  - `PlaybackSessions.watchedThreshold` (a `var`, default `PlaybackTracker.DEFAULT_WATCHED_THRESHOLD` 0.85), the retry on an expired URL (RETRYING, then ERROR with `nextVideo`), `VideoChoices` (`find`, `next`) and `MpvLog`;
  - the external tracks are added over IPC; the first subtitle track is selected, and the first audio track only when the file has none of its own;
  - the videos come in the source's order (`fetchEpisodeVideos`; each hoster's `List<Video>` as the source returns it, which already applies the source's own `sort()` and its quality preference). Nothing is sorted by a Madoyomi preference yet;
  - the subscription `playbackEvents` (STARTED, PROGRESS, WATCHED, ENDED, RETRYING, ERROR, CLOSED).
- app:
  - `views/SettingsView.vue`: General (theme, language), **Playback** (one switch, "Auto-play the next episode", `stores/playerPrefs.ts`, `localStorage` `madoyomi.autoPlayNext`, on by default), Backend, and the anime sources' settings;
  - `utils/progress.ts`: `WATCHED_THRESHOLD` 0.85, the picker's resume rule (1 % to the threshold);
  - `components/VideoPicker.vue` (the remembered choice per source in `localStorage` `madoyomi.videoChoices`, "Source's pick", "Last used"; reopened after a failure with the next video), `NextEpisodeToast.vue`, `composables/useNextEpisode.ts` (a 10 s countdown, auto-play with the remembered choice, else the source's pick, via `utils/videos.ts` `initialPick`);
  - `stores/playback.ts` (the live session, `retrying`, the last ENDED and ERROR).
- Live data in tools/dev-data/agent/live-data (AnimeGG, One Piece, anime id 61, 1167 episodes; episode id = source order for the first ones; KickAssAnime installed too, Bungou Stray Dogs 5th Season ep. 1 is id 1168, an HLS with 8 subtitle and 2 audio tracks): episodes 1 and 2 watched; 3–7 unwatched, position 0 (their durations are known). The `pnpm tauri dev` webview remembers `[DUBBED] AnimeGG:360p` as AnimeGG's last choice, and has auto-play on.

Baseline (the end of Stage 4; rerun at the start as a check, and the counts must not drop):
- server: `.\gradlew.bat build` green with 733 tests, 0 failures: core 648 (8 skipped), AndroidCompat 7, ext-host 8, api 70. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml, after deleting the old *.xml first (a build that fails to compile a module leaves its previous results there);
- tools/smoke-api.ps1 -NoBuild: 24 steps pass;
- app: `pnpm test` gives 40 files (39 + 1 skipped), 325 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
- the live spec (tools/dev-data/agent/seed-live.ps1): 9/9 with 38 documents validating;
- app/src-tauri: `cargo test` 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh). Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done. **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Scratch helpers there (gitignored): app-start.ps1 (starts `pnpm tauri dev` on live-data, hidden, output in tauri-dev.log, and waits for "healthy after"; it inherits `MADOYOMI_MPV`), app-stop.ps1 (closes the window, then lists what's left), ipclib.ps1 (`MpvPipe`, `WaitMpvPipe`, `Ipc`, `Props`), stored.ps1 (a server of its own on live-data to read episodes; only while the app is closed), proxy-start.ps1 (the header proxy on 18080, output in proxy.log), mpvwrap\mpvwrap.exe (a stand-in for `MADOYOMI_MPV`: each launch takes the next line of mpvwrap\plan.txt, `pass`, `proxy` or `after=<s>`, and runs the real mpv; empty plan = pass), liveplay4.ps1 (Stage 4's API checks: `-Only 'measure,early,midplay,twice'`). node_modules has only Windows binaries, so pnpm doesn't run in the VM.
- PowerShell 5.1 wraps a native command's stderr as errors: run pnpm through `cmd /c "pnpm ... 2>&1"` and read `$LASTEXITCODE`. Vitest's summary has ANSI codes: strip them (`-replace '\x1b\[[0-9;]*m', ''`) before matching "Tests ".
- Gradle: run `ktlintFormat` and `build` as two separate invocations (together they fail on an implicit task dependency, `:core:generateJte`). Start it through `Start-Process` with `-PassThru` and `WaitForExit()` and an absolute `JAVA_HOME` (D:\Projects\madoyomi\tools\bin\jdk-21): a Gradle daemon keeps a redirected pipe open, so `cmd /c gradlew ... > file` inside a runner job never returns. Don't rebuild while a backend runs from the jar. A backticked Kotlin test name can't contain `:` on Windows (INVALID_CHARACTERS).
- `ExtensionThreadsTest` ("source-pool threads … don't keep its class loader") is flaky: it failed once in Stage 4 on the shared pool's size and passed on the rerun. Rerun the build once before suspecting your change.
- In PowerShell scripts, `R` is an alias (Invoke-History), so don't name a function that. `$Args` is automatic, `Sequence` is reserved, `$pid` is read-only, and names are case-insensitive (a `[string]$Pick` parameter constrains `$pick`). Keep .ps1 files ASCII.
- The LongString scalar takes a string literal (`lastSecondSeen: "60000"`), not an int.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR='D:\Projects\madoyomi\tools\dev-data\agent\live-data'`; wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log. Close it with `(Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow()` (pnpm then prints `[ELIFECYCLE] ... exit code -1`; the shell's "backend stopped (exit code 0)" is what counts). Kill only the pid from sidecar.log: other java processes run (VS Code's Gradle servers). One server per data dir: H2 locks it. Vite hot-reloads edits to app files; press F5 in the window to be sure a change is loaded.
- Computer use: resolve the app by its path, D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe, while it runs (the name "Madoyomi" doesn't resolve), and mpv as "MPV Player". **Don't call open_application on the app**: it starts a second madoyomi.exe on the default data dir (%APPDATA%\Madoyomi); the dev window is already there, so take a screenshot instead. It can't type into terminals. Take a screenshot before each click: the Claude window may cover part of the app, and the bulk bar shifts the rows down when it appears. Release the lock at the end.
- mpv: C:\Program Files\MPV Player\mpv.exe, on PATH. The backend talks to it over `\\.\pipe\madoyomi-mpv-<session id>`, and mpv accepts several clients (`MpvPipe` in ipclib.ps1 finds it). Mute mpv in automated steps, and wait for a non-zero `duration` before a seek. `quit` over IPC exits with code 0.
- AnimeGG's Play can take 10–20 s the first time (the videos are fetched first). Its one hoster is `no_hoster_list`, with 5 videos (1080p, 720p, 480p and 360p SUBBED, and 360p DUBBED); its videos have no external tracks, so the language preferences need KickAssAnime (8 subtitles, 2 audio tracks, each with a label like "English (eng)").
- The token must stay out of logs, URLs and mpv's command line. At the end, check sidecar.log, madoyomi.log, mpv.log and the dev output for `token=`.

Tasks:
0. Branch: `git switch main`, `git pull`, `git switch -c feat/phase-4-settings`. If feat/phase-4-next isn't merged into main yet, stop and tell me.
1. Rerun the baseline (the server only if you'll change it before the first build; otherwise its counts are the start). Record it in §10 "Stage 5".
2. Plan the stage briefly, then implement:
   a. **Where the settings live:** the backend uses the threshold (the watched mark), the mpv path (`MpvLocator`) and probably the sorting and the track languages, so decide whether these are backend settings (persisted in the data dir, through the API) or device preferences sent with each play, and record why in §9. Auto-play may stay a device preference; say where it ends up.
   b. **The watched threshold** (default 85 %): the backend's watched mark and the app's resume rule (`WATCHED_THRESHOLD`) both follow it; decide its range and step, and whether a running session picks up a change.
   c. **Preferred quality and server (step 9):** sort each hoster's videos (and the hosters) by the preference after the source's own order (`List<Video>.sort()` first, as the step says). Decide how this ranks against the remembered choice per source (Stage 3) and the "Source's pick" mark, and how it affects auto-play's pick, the picker's preselection and Stage 4's `VideoChoices.next`.
   d. **Preferred subtitle and audio language:** choose between the external tracks (and the file's own, if mpv lists them) by language when the session adds them over IPC, in place of "the first subtitle track"; decide how a label like "English (eng)" or a bare "English" is matched, and what happens when nothing matches (keep Stage 2's rule) or the user wants no subtitles.
   e. **The mpv path override:** a path field with a check (exists, runs `mpv --version`?) that `MpvLocator` uses first; an empty field falls back to `MADOYOMI_MPV`, then PATH; `PLAYER_NOT_FOUND` names the setting when it is the override that's wrong.
   f. **The Settings section (step 16):** a Player section (or Playback renamed) with these controls, each change taking effect without a restart.
   g. Tests: unit tests for the sorting, the language matching and the locator's override (the server), the API for the settings if they are the backend's, and component and store tests for the section (the app).
3. Live, through the API and in `pnpm tauri dev` (computer use):
   - a changed threshold: an episode marked watched at the new value (a seek over IPC), and the picker's resume prompt following it;
   - a preferred quality: the picker's order or preselection and auto-play's pick follow it on AnimeGG;
   - a preferred subtitle and audio language on KickAssAnime: mpv's selected `sid`/`aid` match it (read over IPC);
   - an mpv path override: a wrong path is `PLAYER_NOT_FOUND` naming the setting, the right one plays, and clearing it falls back;
   - afterwards, put live-data and the webview's preferences back roughly as they were (episodes 1 and 2 watched, the rest unwatched with position 0; the remembered choice `[DUBBED] AnimeGG:360p`; auto-play on);
   - check for `token=` in the logs and the dev output.
4. Phase doc:
   - §5: tick "Preferred quality/server sorting" and "Player settings section" only if verified;
   - §6: nothing new is expected (the exit run repeats the criteria from a fresh data dir);
   - §8: Stage 5 *Done* (2026-…), with a summary; §9 "Stage 5" decisions; §10 "Stage 5" findings (the baseline, counts after, the live table, what wasn't checked and why).
5. server/UPSTREAM.md for any server change (every upstream edit gets a `// Madoyomi:` comment); player/IPC.md for any change to the tracks, the command line or the events.
6. docs/PLAN.md:
   - Phase 4 status: "Stage 5 of 6 done", with what Stage 5 added, and "Next: Stage 6, the exit run";
   - Phase 4's milestones are all ticked already, so tick nothing new; update §8 "First tasks", §6 risks and §7 (nothing new expected).
7. README.md, matching the phase doc and PLAN.md:
   - the status box (Stage 5 of 6, the player settings);
   - the "Play an episode" bullet (the settings);
   - the API bullets, for any new or changed API;
   - the Roadmap row for Phase 4;
   - "Decided so far in Phase 4": this stage's decisions (and "Where mpv comes from" now that the override exists);
   - the commands tables and the NOTE on pnpm tauri dev, only if they change.
8. At the end, write the ready-to-paste prompt for Stage 6 (the exit run: the step 20 format matrix of MP4, HLS, MKV with ASS, external subtitles, multiple audio tracks and expiring URLs; every §6 criterion from a fresh data dir; a fix round; then close Phase 4 in the docs) into NEXT-PROMPT.md (overwrite it), and repeat it in your final message. Use this same format: current state, baseline test counts, gotchas, the tasks (task 0 is the branch switch to feat/phase-4-exit, off an up-to-date main; include the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 325 + 9 skipped, plus the new tests). If the server changed: `.\gradlew.bat build` ≥ 733 green, smoke 24/24, and the live spec 9/9 with ≥ 38 documents. If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The live checks are recorded in §10. The §5 ticks are only items that passed, and §8 shows Stage 5 *Done*.
- docs/PLAN.md: Phase 4's milestones stay ticked, and §4/§8 show "Stage 5 of 6 done".
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md holds the Stage 6 prompt.
- `git status`: on feat/phase-4-settings, with only text files. None of these:
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
