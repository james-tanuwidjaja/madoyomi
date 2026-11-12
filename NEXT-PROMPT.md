Start Phase 5 (docs/phases/phase-5-manga-reader.md), Stage 1: read the phase doc and agree its stage plan with me first, then implement the first stage of that plan.

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 5 and its milestones, §6 risks, §7 open decisions, §8 "First tasks");
- the Phase 5 doc, all of it. It has §1–§8 (goal, inputs, deliverables, steps 1–17, the checklist, the three exit criteria, risks, and the two decisions to close: the default reader mode per content type, and the cache's default size and location), but no stage plan, decision record or findings log yet: add §8 "Stage plan", §9 "Decision record" and §10 "Findings log" in the shape the Phase 3 and 4 docs use;
- docs/phases/phase-4-playback.md §8 (how a stage plan is written and marked *Done*), §9 and §10 "Stage 6" (the exit run, the latest baseline);
- docs/phases/phase-3-frontend-mvp.md §9 and §10 (the manga side of the UI: the chapter list, `MangaDetailsView`, the chapter-page and cover caches moved into `<data dir>/cache` in the Phase 3 exit run, the manga settings that need a read first);
- server/UPSTREAM.md (the manga side is upstream Suwayomi's: its chapter pages, page images and their cache, and the "Id-keyed caches in the data dir" edit);
- README.md (the status box, the Roadmap row for Phase 5, the commands tables) and app/README.md.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-12, Phase 4 done on feat/phase-4-exit, which I commit and merge before this stage):
- Phases 0–4 are done. Anime: browse, details, episodes, the video picker, playback in mpv (its own window, owned by the backend), progress, the watched mark, the next episode, the retry on expired URLs, Settings → Player. Phase 4's exit run passed the format matrix and every exit criterion from a fresh data dir (phase-4 §10 "Stage 6").
- Manga: repos, extensions (Keiyoushi's `index.pb`), browse with filters, details with a virtualized chapter list, the library and source settings work in the UI (Phase 3). Chapters don't open yet: there is no reader. The backend is upstream Suwayomi's for manga: `fetchChapterPages` gives a chapter's page URLs, and its page route serves the images through the source with a cache in `<data dir>/cache/manga` (`tools/smoke-api.ps1` step 5 reads a MangaPill chapter down to a page image). The app's chapter type already has `lastPageRead`. Check what upstream already offers (the page route and its headers, the cache, `updateChapter(s)` for the read state and `lastPageRead`, prefetch) before planning backend work: Phase 5's `ImageProxy` may largely exist.
- Phase 3's live spec seeds MangaPill and MangaDex (Keiyoushi); comix needs a real WebView (deferred). A 100+ page webtoon chapter is needed for the exit criterion: find a source that has one among the 10-extension manga sample (docs/research/manga-compat.md).

Baseline (the end of Phase 4; rerun at the start as a check, and the counts must not drop):
- server: `.\gradlew.bat build` green with 768 tests, 0 failures: core 675 (8 skipped; 9 in some runs), AndroidCompat 7, ext-host 8, api 78. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml, after deleting the old *.xml first (a build that fails to compile a module leaves its previous results there);
- tools/smoke-api.ps1 -NoBuild: 24 steps pass;
- app: `pnpm test` gives 40 files (39 + 1 skipped), 338 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm build` are clean;
- the live spec (tools/dev-data/agent/seed-live.ps1): 9/9 with 42 documents validating;
- app/src-tauri: `cargo test` 17; `cargo build`, `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh). Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 242). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Git runs there too (`git switch`, `git pull`): the VM can't delete files in the repo, which git needs. Make the branch before your first edit: an edit made on main only moves with `git switch -c` because it is uncommitted. Scratch helpers there (gitignored): app-start6.ps1 (`-DataDir <name under agent\>`, default fresh-data; starts `pnpm tauri dev` hidden, output in tauri-dev.log, waits for "healthy after"; `-Mpv` sets `MADOYOMI_MPV`), app-stop.ps1 (closes the window, then lists what's left), front.ps1 (brings the app window forward and maximizes it), stored6.ps1 (a server of its own on a data dir to read episodes; only while the app is closed), seed-live.ps1 (seeds live-data and runs the live spec). The VM has ffmpeg and node, but node_modules has only Windows binaries, so pnpm doesn't run in the VM. A `.ps1` written in the VM needs a trailing newline on its last line, or that line is lost when it is concatenated.
- PowerShell 5.1 wraps a native command's stderr as errors: run pnpm through `cmd /c "pnpm ... 2>&1"` and read `$LASTEXITCODE`. Vitest's summary has ANSI codes: strip them (`-replace '\x1b\[[0-9;]*m', ''`) before matching "Tests ".
- Gradle: run `ktlintFormat` and `build` as two separate invocations. Start it through `Start-Process` with `-PassThru` and `WaitForExit()` and an absolute `JAVA_HOME` (D:\Projects\madoyomi\tools\bin\jdk-21): a Gradle daemon keeps a redirected pipe open, so `cmd /c gradlew ... > file` inside a runner job never returns. Use `build --continue`, so api's tests run even when a core test fails. Don't rebuild while a backend runs from the jar. A backticked Kotlin test name can't contain `:` or `;` on Windows.
- Flaky: upstream's `CefTest` downloads a JBR live and sometimes gets `SocksProxyTest`'s transient SOCKS host (`UnknownHostException`; once in Phase 4's exit baseline). Rerun the build once before suspecting your change. `ExtensionThreadsTest` now counts threads by name (Phase 4 Stage 6).
- In PowerShell scripts, `R` is an alias (Invoke-History), so don't name a function that. `$Args` is automatic, `Sequence` is reserved, `$pid` is read-only, names are case-insensitive, a script variable can replace a dot-sourced helper's own (`$proc` broke `Stop-MadoyomiServer`), and `Write-Output` inside a function whose result is assigned to `$null` is lost (use `Write-Host` for diagnostics). Keep .ps1 files ASCII. A script that holds the backend's WebSocket open must send a `ping` at least every 20 s: the server closes an idle one after about 30 s ("Connection Idle Timeout").
- GraphQL: the LongString scalar takes a string literal, not an int. There is no `episode(id)` query: use `episodes(condition: {id: N}) { nodes { … } }` (and check the manga equivalents the same way).
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR` set; wait for a "healthy after" line from this session in <dataDir>/logs/sidecar.log. Close it with `(Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow()` (pnpm then prints `[ELIFECYCLE] ... exit code -1`; the shell's "backend stopped (exit code 0)" is what counts). Kill only the pid from sidecar.log: other java processes run (VS Code's Gradle servers). One server per data dir: H2 locks it. A fresh data dir starts with no repos and no extensions: add Keiyoushi's `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb` and install a manga extension through the UI. The webview's device preferences (pins, sorts, the theme, the anime/manga switch) carry over between data dirs.
- Computer use: resolve the app by its path, D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe, while it runs (the name "Madoyomi" doesn't resolve). **Don't call open_application on the app**: it starts a second madoyomi.exe on the default data dir (%APPDATA%\Madoyomi). Clicks fail while the desktop is in front: run front.ps1 through the runner, then take a screenshot. The grant expires after 30 minutes without computer-use activity: resolve and request it again. It can't type into terminals. A native `<select>`'s drop-down is masked: click it, then use the keyboard (Up/Down, Home, End, a letter, Enter). Take a screenshot before each click: the Claude window may cover part of the app. Release the lock at the end.
- The token must stay out of logs and URLs. Image routes take `?token=`: check that it doesn't reach sidecar.log, madoyomi.log or the dev output (`token=` at the end of the stage), and that the reader's image URLs don't leak it anywhere else.

Tasks:
0. Branch: `git switch main`, `git pull`, `git switch -c feat/phase-5-<topic>` (the topic of the stage you agree with me, e.g. `feat/phase-5-images`). If feat/phase-4-exit isn't merged into main yet, stop and tell me.
1. Rerun the baseline. Record it in the Phase 5 doc's new §10 "Stage 1".
2. Read what exists for manga in the backend and the app (step 1–4's image pipeline against upstream's page route and cache; progress against upstream's chapter fields and `updateChapter(s)`; the chapter list in `MangaDetailsView`). Then propose a stage plan for Phase 5 (each stage ending in something verifiable, the last one the exit run with every §6 criterion from a fresh data dir), and ask me to agree it. Ask me too about the two decisions in §8 if they are mine (the default reader mode per content type; the cache's default size and location), with a recommended default for each.
3. Record the agreed plan in §8 "Stage plan" and the decisions in §9 "Stage 1".
4. Implement Stage 1 of the plan, with tests (backend and app), and verify it live (through the API, and in `pnpm tauri dev` where it is about the UI). Record the findings in §10 "Stage 1".
5. Phase doc: tick in §5 only what is done and verified; §8 Stage 1 *Done* (2026-…) with a summary.
6. server/UPSTREAM.md for any server change (every upstream edit gets a `// Madoyomi:` comment).
7. docs/PLAN.md: Phase 5's milestones (tick only what the phase doc verifies; with a note per milestone as Phase 4's have); the phase status ("Phase 5 in progress: Stage 1 of N done"); §6 risks and §7 open decisions if they change; §8 "First tasks".
8. README.md, matching the phase doc and PLAN.md: the status box (Phase 5 in progress), the Roadmap row for Phase 5 (🟡 In progress, with what Stage 1 added), "Decided so far in Phase 5", and the commands tables if a command changes. app/README.md for new views, components or stores.
9. At the end, write the ready-to-paste prompt for the next stage (Phase 5, Stage 2 of the agreed plan) into NEXT-PROMPT.md (overwrite it), and repeat it in your final message. Use this same format: current state, baseline test counts, gotchas, the tasks (task 0 is the branch switch off an up-to-date main; include the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 338 + 9 skipped, plus the new tests). If the server changed: `.\gradlew.bat build` ≥ 768 green, smoke 24/24, and the live spec 9/9 with ≥ 42 documents (more if the stage adds operations). If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The Phase 5 doc has §8 "Stage plan" (agreed, Stage 1 *Done*), §9 "Stage 1" and §10 "Stage 1" (the baseline, the counts after, the live checks, what wasn't checked and why). The §5 ticks are only items that passed.
- docs/PLAN.md: Phase 5 in progress with Stage 1 done; only verified milestones ticked; §8 pointing at Stage 2.
- README.md's status box, Roadmap row and "Decided so far in Phase 5" match the Phase 5 doc and PLAN.md.
- NEXT-PROMPT.md holds the Stage 2 prompt.
- `git status`: on feat/phase-5-<topic>, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv binaries, video files, cached page images;
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
