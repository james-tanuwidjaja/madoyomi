Start Phase 6 (docs/phases/phase-6-library-features.md), **Stage 4: manga downloads**. Stage 3 (the update checker) is on the branch `feat/phase-6-updates`, waiting for my commit and merge.

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 6, its milestones, §6 risks, §7 open decisions, §8 "First tasks");
- the Phase 6 doc, all of it: §4 steps 9–10 and 12–14, §5 (Downloads, and Tests: "Download worker tests"), §6 exit criteria (the CBZ chapter opening offline), §7 risks, the §8 stage plan (Stage 4's line says what verifies it), §9 "Stage 1" to "Stage 3" (how decisions and defaults are recorded; Stage 3 shows the categories' "Downloads" flag, `includeInDownload`, and stores it without using it), and §10 "Stage 3" (a baseline, the counts after, the live API and UI checks, "Not checked");
- server/UPSTREAM.md ("Library updates (Phase 6)" for the latest upstream edit and its entry, "History and badges (Phase 6)", "Manga reader (Phase 5)" (the page route and `madoyomi.core.reader`'s page cache), "Database migrations": the last is `M0073`), and upstream's manga downloads, which may do most of this already: `suwayomi.tachidesk.manga.impl.download` (`DownloadManager`, `Downloader`, the `model` package: `DownloadQueueItem`, `DownloadState`, `DownloadStatus`, `DownloadUpdate`; `fileProvider`: `ChaptersFilesProvider`, `ArchiveProvider` (CBZ), `FolderProvider`, `FileDownloader`, `FileRetriever`), `DownloadMutation`, `DownloadQuery`, `DownloadSubscription`, `serverConfig.downloadAsCbz`, `downloadsPath`, `autoDownloadNewChapters` and its limit, `maxSourcesInParallel` (shared with the update check), and how upstream's reader serves a downloaded chapter (`Page.getPageImage`, which Phase 5 already edited once);
- README.md (the status box, the Roadmap, "Decided so far in Phase 6", the commands tables) and app/README.md;
- the app's code: `views/DownloadsView.vue` (the placeholder), `components/ChapterList.vue` and `EntryItemList.vue` (the row actions and the bulk bar), `views/ReaderView.vue`, `composables/usePageLoader.ts` and `api/operations/reader.ts` (how pages are fetched), `views/UpdatesView.vue` and `composables/useLibraryUpdates.ts` (Stage 3's view, a model for a list fed by a subscription), `api/realtime.ts` (the app-wide subscriptions), `components/CategoriesDialog.vue` (the Downloads flag), `api/operations/`, `api/__tests__/operations.live.spec.ts`.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-15, end of Phase 6 Stage 3):
- Stages 1–3 are done. Stage 1: categories, one list per kind. Stage 2: history and badges. Stage 3: Madoyomi's own update check for both kinds (`madoyomi.core.library.LibraryUpdater`), in place of upstream's manga-only `Updater`, whose 12 h schedule is off (one upstream edit in `Updater.scheduleUpdateTask`).
  - **How it checks:** upstream's category rule and chapter diff; one entry at a time per source, 1 s apart, at most `maxSourcesInParallel` (6) sources at once; completed entries skipped (a setting).
  - **Settings and marks:** the settings are in `<data dir>/library-update.json`. The schedule is off by default, and so is the toast. The "new" marks are in `M0073_LibraryUpdateMarks`.
  - **GraphQL:** `libraryCheck`, `startLibraryUpdate`, `stopLibraryUpdate`, the `libraryUpdates` subscription, `libraryUpdateSettings`/`setLibraryUpdateSettings`, `animeUpdates`/`mangaUpdates`, `updateMarks`, `clearUpdateMarks`.
  - **The app:** the Updates route with the sidebar count, Update library / "Update <tab>" with Stop, "N new" on the cards, Settings → Updates, the Categories dialog's Updates and Downloads flags, and the Windows toast through `tauri-plugin-notification`.
- My decisions so far in Phase 6 (§9): one category list per kind; anime downloads with ffmpeg, yt-dlp as the fallback; backups as gzipped protobuf named `.madoyomi-backup`; History follows the anime/manga switch; the update schedule and the toast are off on a fresh install.
- PLAN.md's milestones: Categories, History and "Scheduled update checker + Updates view + badges" ticked. "Download queue (manga CBZ, anime incl. HLS)" is Stages 4–5's to tick together: tick it only after Stage 5, when every Downloads item of §5 is verified.
- Stage 4 (the §8 plan): the persistent queue (`download`), workers with pause, resume, cancel, retry, reorder and concurrency limits, chapters as CBZ with `ComicInfo.xml` under `downloads/manga/{source}/{title}/`, staged outside the page cache's folder (phase-5 §10, Stage 1 "Not checked"), the reader reading the CBZ, the Downloads screen and the chapter list's download actions (single, range, "next N unread"). Verified by worker state-machine and CBZ tests, and live: a chapter downloaded, then read with the network off.
- Planning points for you, not decisions for me unless they change what I'd see:
  - how much of upstream's `DownloadManager`/`Downloader` to reuse: its queue is in memory or in the DB? Check what survives a restart. Its per-source limits, its CBZ writer: does it write `ComicInfo.xml`? Its subscription. Whether Stage 5's anime downloads can join the same queue or need their own, and how the Downloads screen shows both kinds;
  - where downloads live: upstream's `downloadsPath` in `server.conf` or `<data dir>/downloads`, and whether the folder is a setting;
  - how the reader prefers the local CBZ (upstream's page route already does for a downloaded chapter? check what the GraphQL path really does);
  - the "downloaded" mark on chapter rows and the library cards (§3 "downloaded indicator");
  - what the Categories dialog's Downloads flag does now: auto-download of new chapters after an update check (upstream's `autoDownloadNewChapters`, off by default)? Ask me if turning that on by default is a real choice.
- Baseline to rerun before any edit (Stage 3's end):
  - server: `.\gradlew.bat build` **821** tests, 0 failures (core 695 with 8 skipped, AndroidCompat 7, ext-host 8, api 111); smoke-api.ps1 -NoBuild **24/24**;
  - app: 66 files (65 + 1 skipped), **652 passed + 14 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **14/14** with 85 documents;
  - `cargo test` **17**; build, clippy and fmt clean.
  - Record it in phase-6 §10 "Stage 4".

Gotchas (Phase 5's and Stages 1–3's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 443). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written.
  - Deleting files from the VM is off by default (`rm` fails with "Operation not permitted"); leave scratch files in the gitignored tools/dev-data.
  - Git runs there too. The branch switch is a job with `cmd /c "git switch main 2>&1"`, `git pull`, `git switch -c …`; copy queue\419-branch.ps1 with the new branch name.
  - **Don't write files into app/ while a check job reads it** (prettier, tests); `pnpm format` also rewrites app/README.md (its tables).
  - The VM has ffmpeg, node and Python with Pillow, but node_modules has only Windows binaries, so pnpm doesn't run in the VM. A `.ps1` written in the VM needs CRLF or at least a trailing newline.
  - Scratch helpers in tools/dev-data/agent (gitignored):
    - app-start6.ps1 (`-DataDir <name under agent\>`): starts `pnpm tauri dev` hidden, output in tauri-dev.log (overwritten each start), and waits for "healthy after". Environment variables set in the job before it reach the backend (Stage 3 used `$env:MADOYOMI_UPDATE_INTERVAL_SECONDS`);
    - app-stop.ps1, front.ps1, ipcdo.ps1 (mpv's IPC pipe: `-Mute -Seek <s> -Wait <s> -Quit`, to stop a playback from a job), mem.ps1 (`-Label`), seed-live.ps1;
    - reader-live1/2/3.ps1: they start their own server, so never run them while the app runs on the same data dir;
    - **h2sql.ps1** (`-DataDir <name> -Sql "…;"`): runs SQL on a data dir's H2 database with the H2 jar from the Gradle cache. Only while the app and server on it are stopped. Tables: `ANIME`, `EPISODE`, `MANGA`, `MANGAUSER`, `CHAPTER`, `CHAPTERUSER`, `PAGE`, `CATEGORY`, `ANIME_CATEGORY`, …;
  - Job templates in queue\, copy them to a new number:
    - 395-check.ps1: format, format:check, typecheck, lint, test, build;
    - 394-server.ps1: ktlintFormat, then build with the test counts per module and the failed suites, then smoke. Change its `394-*.out` names to the new number;
    - 423-one.ps1: ktlintFormat, then chosen test classes of `:api` and `:core`; edit its `--tests`;
    - 432-cargo.ps1: cargo fmt, test, build, clippy, fmt --check;
    - 396-live.ps1: seed-live and the live spec on live-data;
    - 420-baseline.ps1: the whole baseline. Change its `420-gradle.out` to the new number;
    - 401-stop.ps1.
- **Run the baseline before writing anything into app/.** A baseline job that reads app/ while edits land has to be rerun on a clean `git worktree` of main with `node_modules` junctioned; remove the junction with `rmdir` before `git worktree remove`, or it may follow it. Server files can be edited once the baseline's gradle part is done.
- PowerShell 5.1:
  - it wraps a native command's stderr as errors, so run pnpm and git through `cmd /c "... 2>&1"` and read `$LASTEXITCODE`;
  - it drops an empty-string argument to a native command, so leave such flags out;
  - strip ANSI codes before matching "Tests ";
  - `R` is an alias, `$Args` automatic and `$pid` read-only; keep .ps1 files ASCII.
- Gradle (only if the server changes):
  - `ktlintFormat` and `build` as two invocations, through `Start-Process -PassThru` + `WaitForExit()` with `JAVA_HOME` D:\Projects\madoyomi\tools\bin\jdk-21; `build --continue`; delete the old test-result *.xml first.
  - **Flaky: `CefTest` fails with `IOException: proxy.example` or `IOException: bad host` when `SocksProxyTest`'s proxy leaks; rerun the build.** `ExtensionThreadsTest` may skip one more case (its pool-size assumption).
  - ktlint wants a blank line before a commented declaration; keep lines under 140 characters (a KDoc line too). ktlintFormat rewrites files on the Windows side, so re-read a file before editing it again.
  - A single class: `.\gradlew.bat :api:test --tests <fqcn>`.
  - API tests extend `ApiGraphQLTest` (a fresh in-memory H2 per test, every migration run) or `AnimeApiTest` (`register(FakeAnimeSource)`, `animeRow`, `episodeRow`). A manga row needs `url`, `title`, `sourceReference` and `memo`, with `initialized = true` so upstream doesn't queue a fetch (see `MangaNewEntryCategoriesApiTest`, `HistoryApiTest`).
  - A fake manga source is a `Source` registered with `GetSource.registerSource(id to source)`; see `FakeMangaSource` in `LibraryUpdateApiTest`.
  - The test config allows fewer parallel sources than the default, and anime and manga ids are separate sequences (both start at 1).
  - A GraphQL `Long` is the `LongString` scalar: pass `"5000"`, not `5000`, in a test's literal.
  - Madoyomi resolvers get the user from `env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()` (see `HistoryGraphQL`).
- A server change needs an entry in server/UPSTREAM.md for every upstream edit, with a `// Madoyomi:` comment at the edit; new Madoyomi code goes under `madoyomi.core.*`. A new table is a new migration (`M0074_…`, in upstream's migration package; see "Database migrations", and M0072/M0073; add its tables to `MigrationsFromEmptyDbTest`'s `NEW_COLUMNS`/`NEW_FOREIGN_KEYS`). New GraphQL operations in the app go into `api/operations/` and the live spec, which validates every document against the schema (85 documents now). **Upstream's GraphQL can differ from its REST routes** (Stage 1's new-entry flag), and upstream may run more than you expect (Stage 3: its own scheduled manga update was running): check what the GraphQL path really does before relying on upstream behaviour.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR`; wait for "healthy after" in <dataDir>/logs/sidecar.log; close with app-stop.ps1; kill only the pid from sidecar.log. One server per data dir. The app runs the newest `server/api/build/Madoyomi-Server-*.jar`: rebuild it (the 394-style job) before a live check of a server change. A hot reload remounts the page you are on. Pre-bundle any new lazily imported dependency in vite.config.ts.
- **Data dirs** (`tools\dev-data\agent\<name>`, gitignored):
  - a fresh one starts with no repos and no extensions; the webview's localStorage is per user profile, not per data dir;
  - `seed-live.ps1` seeds a data dir and runs the live spec;
  - `live-data` has AnimeGG, KickAssAnime, MangaPill, MangaDex and Webtoons.com installed;
  - `p6-ui` has 6 anime and 7 manga in the library (anime ids 1–4 and 6, "Oshi no Ko" id 1 with 11 episodes). Kingdom (manga 51, MangaPill, 928 chapters, in Reading) is the one manga with chapters: the six MangaDex entries have none and fail "No chapters found";
  - Stage 3 left `p6-ui`'s update schedule off (`library-update.json`).
  - "Reading the network off" for the offline check: stop the source (a firewall rule for the JVM, or a proxy setting) and say how; ask me if that needs admin rights.
- Computer use:
  - resolve the app by D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe while it runs (not by name); **don't call open_application on it**. Run front.ps1 through the runner, then screenshot (a click while the desktop is in front fails).
  - At scale 0.5 the coordinate frame is 1456×819; read button positions from a full screenshot, not from a zoom. The Claude window floats over the top right and takes the keyboard focus after each message: click inside the app before a key.
  - A native `<select>`'s list is masked: pick an option by typing its first letters and Return. Windows' notification popups can't be granted: ask me whether a toast showed. mpv's window is masked: stop it with ipcdo.ps1 `-Quit` through the runner.
  - In the reader the toolbar hides while paging and a click on the left or right third turns a page: click the middle to show the toolbar before "← <manga>".
  - Windows drops injected input while an elevated window is in front. The connection can drop and lose the app grant: request access again. Release the lock at the end.
- Don't read files outside the repo and its tools (e.g. Windows' own notification store): such reads are refused.
- The token must stay out of logs and URLs: check that `token=` doesn't reach sidecar.log, madoyomi.log or the dev output.

Tasks:
0. Switch to a new branch off an up-to-date main: `git switch main`, `git pull`, `git switch -c feat/phase-6-manga-downloads`. If Stage 3 (`feat/phase-6-updates`) isn't merged into main yet, stop and tell me.
1. Rerun the baseline before any edit (above) and record it in phase-6 §10 "Stage 4".
2. Read the download code above, then plan Stage 4 briefly and send it to me, with any decision that is genuinely mine (your recommendation first, and what each option costs). Wait for my answers if you asked any; otherwise build.
3. Build Stage 4:
   - the persistent manga download queue (it survives a restart), with workers that pause, resume, cancel, retry and reorder, and concurrency limits (global and per source, staggered like the update check);
   - chapters written as CBZ with `ComicInfo.xml` under `downloads/manga/{source}/{title}/`, outside the page cache's folder;
   - the reader reading the CBZ (with the network off too), and a downloaded indicator on chapter rows (and the library cards if cheap);
   - the Downloads screen (the queue and the completed ones, live over a subscription) and the chapter list's actions (single, range, "next N unread");
   - the Categories dialog's Downloads flag doing what you decided;
   - server tests (the worker state machine, the CBZ writer with `ComicInfo.xml`, the queue across a restart, any migration), app tests, and the new operations in the live spec;
   - check it live through the API and in `pnpm tauri dev`: a chapter downloaded, then read with the network off.
4. Phase doc:
   - tick in §5 only what is done and verified: Downloads "Persistent download queue", "Manga → CBZ (+ `ComicInfo.xml`)", "Pause/resume/cancel/retry/reorder + concurrency limits" and "Player/reader use local files" and "Downloads screen + download actions in lists" only as far as manga verifies them (leave a note where anime is Stage 5's); Tests: "Download worker tests";
   - §8 Stage 4 *Done* (date) with a summary; §9 "Stage 4" (decisions and defaults); §10 "Stage 4" (the baseline, the counts after, the live checks, what wasn't checked and why).
5. server/UPSTREAM.md for every upstream edit and new table or migration.
6. docs/PLAN.md:
   - leave the "Download queue" milestone unticked until Stage 5 verifies the anime half;
   - Phase 6's status: "Phase 6 in progress: Stage 4 of 7 done" with a summary;
   - §6 risks and §7 open decisions if they change;
   - §8 "First tasks" pointing at Stage 5.
7. README.md, matching the phase doc and PLAN.md:
   - the status box, the Phase 6 Roadmap row (🟡 with Stages 1–4), "Decided so far in Phase 6" (Stage 4's decisions);
   - the bullets of what the app can do (Downloads, reading offline) and of the backend's API, and the commands tables if a command changes;
   - app/README.md for any code change (routes, operations, composables, stores, components, utils, the shell's plugins and commands).
8. Write the ready-to-paste prompt for Stage 5 (anime downloads) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 652 + 14 skipped, against the baseline). The live spec 14/14 or more, with more than 85 documents if operations were added. If the server changed: `.\gradlew.bat build` ≥ 821 green, smoke 24/24. If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The Phase 6 doc has Stage 4 *Done* in §8, §9 "Stage 4", and §10 "Stage 4" (the baseline, the counts after, the live checks, what wasn't checked and why). §5 ticks only verified items.
- docs/PLAN.md: Phase 6's status matches the phase doc; only verified milestones ticked (the download milestone waits for Stage 5); §8 pointing at Stage 5.
- README.md's status box, Roadmap row and "Decided so far in Phase 6" match the Phase 6 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment) and any new migration.
- NEXT-PROMPT.md holds Stage 5's prompt.
- `git status`: on feat/phase-6-manga-downloads, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv binaries, video files, cached page images, **CBZ files or downloaded chapters**;
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
