Start Phase 4 (docs/phases/phase-4-playback.md), Stage 6: the exit run. The step 20 format matrix (MP4, HLS, MKV with ASS subtitles, external subtitles, multiple audio tracks, expiring URLs), every §6 exit criterion from a fresh data dir, a fix round, then close Phase 4 in the docs.

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it. §5 has what is ticked (only "Manual format matrix passed", the deferred proxy fallback and the `--wid` stretch goal are open), §6 the three exit criteria (each passed live on the seeded `live-data`, not yet from a fresh data dir), §8 the stage plan (Stages 1–5 *Done*, Stage 6 next; the `--wid` prototype is a stretch goal after the exit, not part of it), §9 the decisions (Stage 5: the player settings are the backend's in `<data dir>/player-settings.json`, the remembered choice ranks first, the sorting, the track languages, the mpv path), §10 "Stage 5" (the baseline, the live tables and what wasn't checked: a preferred server reordering hosters live, the file's own tracks chosen by language, a threshold change picked up mid-session);
- player/IPC.md (the include file, the tracks over IPC and "Which tracks play (Stage 5)", "When the video doesn't play (Stage 4)");
- server/UPSTREAM.md, "Playback (Phase 4)";
- README.md (the status box, the Roadmap row for Phase 4, "Decided so far in Phase 4", the NOTE on pnpm tauri dev) and app/README.md;
- docs/phases/phase-3-frontend-mvp.md §10 "Stage 7", for how Phase 3's exit run was recorded.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-12, Stage 5 done on feat/phase-4-settings, which I commit and merge before this stage):
- server (api, `madoyomi.core.player`):
  - `playEpisode`, `stopPlayback`, `playbackSession`, `fetchEpisodeVideos` (sorted by the player settings after the source's order; `sourceIndex`, `matchesPreference`), `fetchHosterVideos`, `updateEpisode(s)`, the subscription `playbackEvents` (STARTED, PROGRESS, WATCHED, ENDED, RETRYING, ERROR, CLOSED);
  - the player settings: `playerSettings`, `setPlayerSettings(input: {patch})`, `playerLanguages`, `mpvCheck(path)`; `PlayerSettingsStore` (`<data dir>/player-settings.json`), `VideoPreferences`, `PlayerLanguages`, `TrackSelection` (`sid`/`aid` set over IPC once the external tracks load; also for a file's own tracks when a language is set), `MpvLocator` (the settings' path, `MADOYOMI_MPV`, PATH);
  - the retry on an expired URL (RETRYING, then ERROR with `nextVideo`), `VideoChoices`, `MpvLog`.
- app: Settings → **Player** (auto-play on this device; "Mark as watched at", quality, server, subtitles, audio, mpv with Check on the backend), `composables/usePlayerSettings.ts`, `VideoPicker` (Source's pick, **Preferred**, Last used; the resume prompt on the backend's threshold), `useNextEpisode` (auto-play: remembered → preference → source), `NextEpisodeToast`, `stores/playback.ts`.
- Live data in tools/dev-data/agent/live-data (AnimeGG, One Piece, anime id 61, 1167 episodes; KickAssAnime, Bungou Stray Dogs 5th Season ep. 1 is id 1168, an HLS with 8 subtitle and 2 audio tracks): episodes 1 and 2 watched; 3–7 unwatched, position 0; 1168 at 2001 ms; the player settings at their defaults. The `pnpm tauri dev` webview remembers `[DUBBED] AnimeGG:360p` for AnimeGG and "VidStreaming - 720p (1280x720) - 836 kbps" for KickAssAnime, and has auto-play on. The exit run itself uses a **fresh** data dir.

Baseline (the end of Stage 5; rerun at the start as a check, and the counts must not drop):
- server: `.\gradlew.bat build` green with 766 tests, 0 failures: core 674 (8 skipped), AndroidCompat 7, ext-host 8, api 77. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml, after deleting the old *.xml first (a build that fails to compile a module leaves its previous results there);
- tools/smoke-api.ps1 -NoBuild: 24 steps pass;
- app: `pnpm test` gives 40 files (39 + 1 skipped), 338 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
- the live spec (tools/dev-data/agent/seed-live.ps1): 9/9 with 42 documents validating;
- app/src-tauri: `cargo test` 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh). Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done. **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Git runs there too (`git switch`, `git pull`): the VM can't delete files in the repo, which git needs. Scratch helpers there (gitignored): app-start.ps1 (starts `pnpm tauri dev` on live-data, hidden, output in tauri-dev.log, and waits for "healthy after"; it inherits `MADOYOMI_MPV`; for a fresh data dir set `MADOYOMI_DATA_DIR` first or copy it with another dir), app-stop.ps1 (closes the window, then lists what's left), ipclib.ps1 (`MpvPipe`, `WaitMpvPipe`, `Ipc`, `Props`), stored.ps1 (a server of its own on live-data to read episodes; only while the app is closed), proxy-start.ps1 (the header proxy on 18080, output in proxy.log, with the expiring mode `/expire/<token>`), mpvwrap\mpvwrap.exe (a stand-in for `MADOYOMI_MPV`: each launch takes the next line of mpvwrap\plan.txt, `pass`, `proxy` or `after=<s>`, and runs the real mpv; empty plan = pass), liveplay4.ps1 (Stage 4's retry checks), liveplay5.ps1 (Stage 5's settings checks: `-Only 'sort,threshold,lang,mpv'`). node_modules has only Windows binaries, so pnpm doesn't run in the VM.
- PowerShell 5.1 wraps a native command's stderr as errors: run pnpm through `cmd /c "pnpm ... 2>&1"` and read `$LASTEXITCODE`. Vitest's summary has ANSI codes: strip them (`-replace '\x1b\[[0-9;]*m', ''`) before matching "Tests ".
- Gradle: run `ktlintFormat` and `build` as two separate invocations. Start it through `Start-Process` with `-PassThru` and `WaitForExit()` and an absolute `JAVA_HOME` (D:\Projects\madoyomi\tools\bin\jdk-21): a Gradle daemon keeps a redirected pipe open, so `cmd /c gradlew ... > file` inside a runner job never returns. `build --continue` runs api's tests even when a core test fails. Don't rebuild while a backend runs from the jar. A backticked Kotlin test name can't contain `:` or `;` on Windows.
- Flaky core tests, both unrelated to playback: `ExtensionThreadsTest` ("source-pool threads … don't keep its class loader") failed in 3 of 7 builds in Stage 5: it counts the shared pool's threads, which idle ones from earlier tests distort (§10 "Stage 5"). Upstream's `CefTest` (2 of 7) downloads a JBR live and gets `SocksProxyTest`'s transient `proxy.example`/`bad host` host. Rerun the build once before suspecting your change; the fix round may make `ExtensionThreadsTest` count its own threads by name.
- In PowerShell scripts, `R` is an alias (Invoke-History), so don't name a function that. `$Args` is automatic, `Sequence` is reserved, `$pid` is read-only, names are case-insensitive (a `[string]$Pick` parameter constrains `$pick`), and a script variable can replace a dot-sourced helper's own (`$proc` broke `Stop-MadoyomiServer` in Stage 5). Keep .ps1 files ASCII.
- The LongString scalar takes a string literal (`lastSecondSeen: "60000"`), not an int. There is no `episode(id)` query: use `episodes(condition: {id: N}) { nodes { … } }`.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR` set; wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log. Close it with `(Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow()` (pnpm then prints `[ELIFECYCLE] ... exit code -1`; the shell's "backend stopped (exit code 0)" is what counts). Kill only the pid from sidecar.log: other java processes run (VS Code's Gradle servers). One server per data dir: H2 locks it. A fresh data dir starts with no repos, no extensions and the default player settings: add the yuzono repo and install AnimeGG and KickAssAnime through the UI (as Phase 3's exit run did).
- Computer use: resolve the app by its path, D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe, while it runs (the name "Madoyomi" doesn't resolve), and mpv as "MPV Player". **Don't call open_application on the app**: it starts a second madoyomi.exe on the default data dir (%APPDATA%\Madoyomi); the dev window is already there, so take a screenshot instead. It can't type into terminals. A native `<select>`'s drop-down is masked: focus the select and use the keyboard (Home, End, Up/Down, a letter, Enter). Text typed with a trailing newline doesn't press Enter in a field: press Return after it. Take a screenshot before each click: the Claude window may cover part of the app. Release the lock at the end.
- mpv: C:\Program Files\MPV Player\mpv.exe (v0.41.0), on PATH. The backend talks to it over `\\.\pipe\madoyomi-mpv-<session id>`, and mpv accepts several clients. Mute mpv in automated steps, and wait for a non-zero `duration` before a seek. `quit` over IPC exits with code 0.
- The format matrix needs sources for what AnimeGG (MP4, no tracks) and KickAssAnime (HLS, 8 external subtitles, 2 external audio tracks, no audio of its own) don't cover: an MKV with ASS and several audio tracks of its own (find one among the top-20 yuzono extensions, docs/research/anime-compat.md, or record it as not covered with why), and an expiring URL (the header proxy's expiring mode through mpvwrap, as Stage 4 did; also an HLS one if a playlist's segments can go through it).
- AnimeGG's Play can take 10–20 s the first time (the videos are fetched first).
- The token must stay out of logs, URLs and mpv's command line. At the end, check sidecar.log, madoyomi.log, mpv.log and the dev output for `token=`.

Tasks:
0. Branch: `git switch main`, `git pull`, `git switch -c feat/phase-4-exit`. If feat/phase-4-settings isn't merged into main yet, stop and tell me.
1. Rerun the baseline. Record it in §10 "Stage 6".
2. The step 20 format matrix, live (through the API where it is about mpv, in `pnpm tauri dev` where it is about the UI): MP4 (AnimeGG), HLS (KickAssAnime), MKV with ASS subtitles, external subtitles (KickAssAnime: the language preference, Off, the source's first), multiple audio tracks (external: KickAssAnime; the file's own: the MKV), and expiring URLs (at once, mid-play; the refetch, then the next video). For each: plays, the right tracks are selected (read `sid`/`aid`/`track-list` over IPC), progress saves, the headers arrive. Tick "Manual format matrix passed" only if every row passed or a row is recorded as not coverable with why.
3. Every §6 criterion from a **fresh data dir** in `pnpm tauri dev`, through the UI: watch an episode, close mpv halfway, reopen the app, resume within ±5 s; an episode passing the threshold shows Watched without a refresh; the next episode starts from the end-of-episode prompt. Also the player settings from scratch (the defaults, one change taking effect) and the trust prompt on install.
4. The fix round: fix what steps 2–3 find (each fix with a test where one fits, recorded in §10), and decide whether to fix `ExtensionThreadsTest`'s count. Then rerun everything.
5. Phase doc:
   - §5: tick what passed; the proxy fallback stays deferred and the `--wid` prototype stays open (a stretch goal);
   - §6: the three criteria re-ticked with the fresh-data-dir runs;
   - §8: Stage 6 *Done* (2026-…), with a summary; §9 "Stage 6" decisions (if any); §10 "Stage 6" findings (the baseline, counts after, the matrix table, the §6 runs, the fixes, what wasn't checked and why).
6. server/UPSTREAM.md for any server change (every upstream edit gets a `// Madoyomi:` comment); player/IPC.md for any change to the tracks, the command line or the events.
7. docs/PLAN.md:
   - Phase 4: "**Phase 4 done (2026-…).**" with what the exit run checked, as Phases 2 and 3 have; the milestones stay ticked;
   - §8 "First tasks": Phase 4 done, Phase 5 (manga reader) next;
   - §6 risks (the video-headers row: what the matrix measured) and §7 (nothing new expected).
8. README.md, matching the phase doc and PLAN.md:
   - the status box (Phase 4 done; what works now);
   - the Roadmap row for Phase 4 (✅ Done);
   - "Decided so far in Phase 4" (any Stage 6 decision);
   - the commands tables and the NOTE on pnpm tauri dev, only if they change.
9. At the end, write the ready-to-paste prompt for the next stage (Phase 5, Stage 1: read docs/phases/phase-5-manga-reader.md and agree its stage plan with me first) into NEXT-PROMPT.md (overwrite it), and repeat it in your final message. Use this same format: current state, baseline test counts, gotchas, the tasks (task 0 is the branch switch off an up-to-date main; include the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 338 + 9 skipped, plus any new tests). If the server changed: `.\gradlew.bat build` ≥ 766 green, smoke 24/24, and the live spec 9/9 with ≥ 42 documents. If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The matrix and the §6 runs are recorded in §10, from a fresh data dir. The §5 ticks are only items that passed, and §8 shows Stage 6 *Done*.
- docs/PLAN.md: "Phase 4 done", its milestones ticked, §8 pointing at Phase 5.
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md holds the Phase 5 Stage 1 prompt.
- `git status`: on feat/phase-4-exit, with only text files. None of these:
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
