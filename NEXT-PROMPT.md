Start Phase 6 (docs/phases/phase-6-library-features.md) **Stage 7: the exit run** (§6 exit criteria and a fix round).

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 6, its milestones, §6 risks, §7 open decisions, §8 "First tasks");
- the Phase 6 doc: §5 (every checklist item, and the one left unticked: the Aniyomi import stretch), §6 exit criteria, §7 risks, §8 (the stage plan's Stage 7 line), §9 (every stage's decisions) and §10 (every stage's "Not checked" list, Stage 6's in particular);
- the Phase 5 doc's §8 Stage 6 and §10 "Stage 6" (how Phase 5's exit run was done and written up);
- server/UPSTREAM.md (the Phase 6 sections: "Library categories", "History and badges", "Library updates", "Manga downloads", "Anime downloads", "Backups"; and "Database migrations");
- README.md (the status box, the Roadmap row, "Decided so far in Phase 6") and app/README.md;
- docs/phases/phase-7-packaging-release.md (what comes next, so the exit run's notes point there).

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-16, Phase 6 Stages 1–6 done, merged by me before you start):
- Stages 1–5: categories (one list per kind), history and badges, the update checker (off by default), manga downloads (one queue for both kinds, CBZ read offline), anime downloads (HLS through ffmpeg with yt-dlp as the fallback, subtitles and audio beside the file, played offline).
- **Stage 6, backup and restore:** `.madoyomi-backup` files (gzipped protobuf; upstream's manga fields and models, Madoyomi's from field 20000: the anime library with seasons, episodes, positions, history and categories, the manga categories' flags, the repos, the USER certificates, the installed extensions, the sources' preferences, the settings files; not upstream's server settings with their passwords, the downloads, the queue, the update marks or the device's localStorage). Back up now and a schedule (off by default, the user's; daily or weekly, the newest N kept) in `<data dir>/backups`; upstream's own daily manga backup off (one upstream edit, the user's). Restore: the file checked first, merge (preselected, the user's) or replace, a trust prompt for the repos, certificates and extensions to reinstall, the progress over `madoyomiRestoreStatusChanged`. A Mihon/Suwayomi `.tachibk` restores its manga; an Aniyomi backup's anime part isn't read (the stretch, unticked).
  - **Verified live:** `p6-ui` backed up in the app and restored into a fresh data dir (`p6-fresh`) after the trust prompt (2 repos, 5 extensions reinstalled, about 20 s): the same library, tabs, sorts, badges, marks, History and settings, and the databases the same table by table (snapshot6.ps1). A 40 s schedule keeping 3 pruned the oldest. §6's third criterion and the "Backup / restore round-trip" milestone are ticked (the exit run checks them again).
  - Found and fixed in the stage: the backup list went stale while the schedule pruned (Delete hit `NOT_FOUND`; it now refreshes every 15 s and after a failed delete), upstream's `.tachibk` files labelled "Manual", a restored settings file written on one line. These fixes were checked by tests, not again in the app.
- Baseline counts (main after the merge; rerun them as task 0's baseline and record them in phase-6 §10 "Stage 7"):
  - server: `.\gradlew.bat build` **853** tests (core 703 with 8 skipped, AndroidCompat 7, ext-host 8, api 135); smoke-api.ps1 -NoBuild **24/24**;
  - app: 71 files (70 + 1 skipped), **696 passed + 17 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **17/17** with **109** documents (it downloads two whole episodes at 360p: about 8 minutes);
  - `cargo test` **18**; clippy and fmt clean.

Gotchas (Phase 5's and Phase 6 Stages 1–6's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 553; numbers are per name, so check `ls queue | tail` before picking one). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written.
  - Deleting files from the VM is off by default (`rm` fails with "Operation not permitted"); leave scratch files in the gitignored tools/dev-data.
  - Git runs there too. The branch switch is a job with `cmd /c "git switch main 2>&1"`, `git pull`, `git switch -c …`; copy queue\526-branch.ps1 with the new branch name.
  - **Don't write files into app/ while a check job reads it** (prettier, tests); `pnpm format` also rewrites app/README.md (its tables).
  - The VM has ffmpeg, node and Python with Pillow, but node_modules has only Windows binaries, so pnpm doesn't run in the VM. A `.ps1` written in the VM needs CRLF or at least a trailing newline; a `'` inside a `printf '…'` job breaks the PowerShell (write such jobs with a heredoc).
  - Scratch helpers in tools/dev-data/agent (gitignored):
    - app-start6.ps1 (`-DataDir <name under agent\>`): starts `pnpm tauri dev` hidden, output in tauri-dev.log (overwritten each start), and waits for "healthy after". Environment variables set in the job before it reach the backend;
    - app-stop.ps1, front.ps1 (run it again if the window isn't maximized), ipcdo.ps1 (mpv's IPC pipe: `-Mute -Seek <s> -Wait <s> -Extra tracks -Quit`), mem.ps1 (`-Label`), seed-live.ps1;
    - reader-live1/2/3.ps1: they start their own server, so never run them while the app runs on the same data dir;
    - **h2sql.ps1** (`-DataDir <name> -Sql "…;"`): runs SQL on a data dir's H2 database with the H2 jar from the Gradle cache. Only while the app and server on it are stopped. Tables: `ANIME`, `EPISODE`, `MANGA`, `MANGAUSER`, `CHAPTER`, `CHAPTERUSER`, `PAGE`, `CATEGORY`, `ANIME_CATEGORY`, `ANIME_CATEGORY_ANIME`, `ANIME_HISTORY`, `DOWNLOAD`, `EPISODE_DOWNLOAD`, `TRUSTED_SIGNATURE`, `EXTENSIONSTORE`, `EXTENSION`, …;
  - Job templates in queue\, copy them to a new number:
    - 395-check.ps1: format, format:check, typecheck, lint, test, build;
    - 394-server.ps1: ktlintFormat, then build with the test counts per module and the failed suites, then smoke. Change its `394-*.out` names to the new number;
    - 489-one.ps1: ktlintFormat, then chosen test classes of `:api` and `:core` with each suite's counts, failures and skips; edit its `--tests` and its `489-` names;
    - 432-cargo.ps1: cargo fmt, test, build, clippy, fmt --check;
    - 396-live.ps1: seed-live and the live spec on live-data;
    - 482-baseline.ps1: the whole baseline. Change its `482-gradle.out` to the new number;
    - 401-stop.ps1.
- **Run the baseline before writing anything into app/.** A baseline job that reads app/ while edits land has to be rerun on a clean `git worktree` of main with `node_modules` junctioned; remove the junction with `rmdir` before `git worktree remove`, or it may follow it. Server files can be edited once the baseline's gradle part is done.
- **Don't rebuild the jar while the live spec or the app runs on it** (Windows locks a running jar): wait for the live spec's .done before a build job.
- PowerShell 5.1:
  - it wraps a native command's stderr as errors, so run pnpm and git through `cmd /c "... 2>&1"` and read `$LASTEXITCODE`;
  - it drops an empty-string argument to a native command, so leave such flags out;
  - strip ANSI codes before matching "Tests ";
  - `R` is an alias, `$Args` automatic and `$pid` read-only; keep .ps1 files ASCII.
- Gradle (only if the server changes):
  - `ktlintFormat` and `build` as two invocations, through `Start-Process -PassThru` + `WaitForExit()` with `JAVA_HOME` D:\Projects\madoyomi\tools\bin\jdk-21; `build --continue`; delete the old test-result *.xml first.
  - Flaky: `LooperTest` once failed at LooperTest.kt:78; `CefTest` fails with `IOException: proxy.example` or `IOException: bad host` when `SocksProxyTest`'s proxy leaks; rerun the build. `ExtensionThreadsTest` may skip one more case. (Stage 5's builds were all green on the first run.)
  - ktlint wants a blank line before a commented declaration; keep lines under 140 characters (a KDoc line too). **ktlintFormat rewrites files on the Windows side** (it splits long chains over lines), so re-read a file before editing it again and match the reformatted text, not what you wrote.
  - Write `'\uFEFF'` and other special characters as escapes in Kotlin sources: a literal BOM written from the VM vanished and broke the build.
  - A single class: `.\gradlew.bat :api:test --tests <fqcn>`.
  - API tests extend `ApiGraphQLTest` (a fresh in-memory H2 per test, every migration run) or `AnimeApiTest` (`register(FakeAnimeSource)`, `animeRow`, `episodeRow`). A manga row needs `url`, `title`, `sourceReference` and `memo`, with `initialized = true` so upstream doesn't queue a fetch.
  - A fake manga source is a `Source` registered with `GetSource.registerSource(id to source)`.
  - The test config allows fewer parallel sources than the default, and anime and manga ids are separate sequences (both start at 1).
  - A GraphQL `Long` is the `LongString` scalar: pass `"5000"`, not `5000`, in a test's literal.
  - `ApiGraphQLTest` classes have **one instance for all their tests** (its `@BeforeAll` isn't static): make fakes in `@BeforeEach`.
  - Settings stores have a `fileOverride` and `reload()` for tests (`DownloadSettingsStore`, `ReaderSettingsStore`, `PlayerSettingsStore`, `LibraryUpdateSettingsStore`, `BackupSettingsStore`; `BackupFiles.dirOverride` for the backups folder); put them back in `@AfterEach`.
  - Madoyomi resolvers get the user from `env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()` (see `HistoryGraphQL`).
- A server change needs an entry in server/UPSTREAM.md for every upstream edit, with a `// Madoyomi:` comment at the edit; new Madoyomi code goes under `madoyomi.core.*`. A new table is a new migration (`M0076_…`, in upstream's migration package; see "Database migrations" and M0072–M0075 (Stage 6 added none); add its tables to `MigrationsFromEmptyDbTest`'s `NEW_COLUMNS`/`NEW_FOREIGN_KEYS`, and bump its migration count). New GraphQL operations in the app go into `api/operations/` and the live spec, which validates every exported `*_QUERY`/`*_MUTATION`/`*_SUBSCRIPTION` against the schema (109 documents now). **Upstream's GraphQL can differ from its REST routes**, and upstream may run more than you expect (its own scheduled backup ran daily until Stage 6): check what the GraphQL path really does before relying on upstream behaviour.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR`; wait for "healthy after" in <dataDir>/logs/sidecar.log; close with app-stop.ps1; kill only the pid from sidecar.log. One server per data dir. The app runs the newest `server/api/build/Madoyomi-Server-*.jar`: rebuild it (the 394-style job) before a live check of a server change. A hot reload remounts the page you are on. Pre-bundle any new lazily imported dependency in vite.config.ts.
- **Data dirs** (`tools\dev-data\agent\<name>`, gitignored):
  - a fresh one starts with no repos and no extensions; the webview's localStorage is per user profile, not per data dir (so a "fresh install" check keeps the device's own settings: say which settings are the backend's and which the device's);
  - `seed-live.ps1` seeds a data dir and runs the live spec;
  - `live-data` has AnimeGG, KickAssAnime, MangaPill, MangaDex and Webtoons.com installed;
  - `p6-ui` has 5 anime and 7 manga in the library ("Oshi no Ko" id 1 with 11 episodes, KickAssAnime), anime categories (Watching, Dropped, Later, Live Renamed, Favorites) and manga ones (Reading), history, update marks, Kingdom's chapters downloaded (manga 51, MangaPill) and "Oshi no Ko" Ep. 2 downloaded, the settings at the defaults, the update and backup schedules off, and `backups\Madoyomi_2026-11-16_21-21-29.madoyomi-backup` (Stage 6's live check) beside upstream's 7 old `.tachibk` files;
  - `p6-fresh` is Stage 6's restore of that backup into a fresh data dir (its backup schedule is on, daily, keep 3: turn it off or ignore it);
  - **`snapshot6.ps1`** (`-DataDir <name> -Out <file>`, the app stopped): the library state a backup carries, from the H2 database (entries, episodes' and chapters' marks, positions and history, categories with flags and meta, memberships, repos, installed extensions, trusted keys), for a diff between two data dirs. Its two meta queries fail (H2's `VALUE` keyword; `MM.*`/`CM.*` work, see queue\544-meta.ps1). The `CHAPTER_USER` row count differs after a restore by design (upstream makes one per chapter);
  - **the offline check:** with the app stopped, set in `<data dir>\server.conf`: `server.socksProxyEnabled = true`, `server.socksProxyHost = "127.0.0.1"`, `server.socksProxyPort = "9"`; put them back (`false`, `""`, `""`) afterwards. `p6-ui`'s are back to the defaults.
- Computer use:
  - resolve the app by D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe while it runs (not by name); **don't call open_application on it**. Run front.ps1 through the runner, then screenshot (a click while the desktop is in front fails).
  - At scale 0.5 the coordinate frame is 1456×819; read button positions from a full screenshot, not from a zoom. The Claude window floats over the top right and takes the keyboard focus after each message: click inside the app before a key.
  - A native `<select>`'s list is masked: pick an option by typing its first letters and Return. A native `<select>` that acts on change ("Download…"): open it with a click first, then type and Return. Windows' notification popups and file dialogs may be masked: ask me what showed.
  - In a list the row's checkbox is small (about 222, y+6 from the row's title at full scale); a click a few pixels off opens the item instead. mpv's window is masked: drive it with ipcdo.ps1.
  - Windows drops injected input while an elevated window is in front. The connection can drop and lose the app grant: request access again. Release the lock at the end.
- Don't read files outside the repo and its tools: such reads are refused.
- The token must stay out of logs and URLs: check that `token=` doesn't reach sidecar.log, madoyomi.log or the dev output, nor a backup (`zcat <file> | strings | grep -i token`).
- Backups (Stage 6): a restore names a file in `<data dir>\backups`, so put a backup file there to restore it in the app (the native file picker is masked; the upload route is the live spec's). `MADOYOMI_BACKUP_INTERVAL_SECONDS` (set in the job before app-start6.ps1) shortens the backup schedule while it is on. A restore reinstalls extensions from the network (the repos must answer). The live spec's backup test runs on `live-data`, whose library is empty between tests: its counts are zeros, so compare a real library with snapshot6.ps1.


Tasks:
0. Branch: once I have merged Stage 6, switch to a new branch off an up-to-date main (a job like queue\526-branch.ps1: `git switch main`, `git pull`, `git switch -c feat/phase-6-exit-run`). If Stage 6 isn't merged yet (main has no `server/core/src/main/kotlin/madoyomi/core/backup/`), stop and tell me. Then run the whole baseline (a 482-style job, its gradle output renamed) **before writing anything into app/**, and record it.
1. Plan the exit run briefly: every §6 criterion, from a **fresh data dir** (a new name under tools\dev-data\agent, no repos, no extensions), in `pnpm tauri dev`:
   - the setup a user would do: add the two repos, install and trust the extensions (AnimeGG, KickAssAnime, MangaPill at least), add a few anime and manga to the library, make categories;
   - **updates:** a scheduled check (`MADOYOMI_UPDATE_INTERVAL_SECONDS`) finds new episodes and chapters (take some out with h2sql.ps1 first, as Stage 3 did), they show in Updates and as badges in the Library;
   - **downloads offline:** an HLS episode (KickAssAnime) and a CBZ chapter (MangaPill) downloaded, then opened with the sources cut off (the SOCKS proxy in server.conf; put it back);
   - **backup → fresh install → restore:** back the new data dir up, restore it into a second fresh one after the trust prompt, compare (the app, and snapshot6.ps1 on both), Replace too this time (a change after the backup, then Replace, and check it is gone);
   - the Stage 6 fixes in the app (the list refreshing while a short schedule prunes; a `.tachibk` labelled as such);
   - each stage's "Not checked" items that can be checked now (§10), and the History, categories and the Downloads screen once more, after an app restart;
   - the token out of the logs and the backups.
2. A fix round: fix what the run finds, with tests, and rerun the checks it touches. If time allows (and only then), the stretch: read an Aniyomi backup's anime part (its fields 501–506: `BackupAnime`, `BackupAnimeCategory`, `BackupAnimeSource`; sources matched by id) into the restore, with a test; otherwise leave it noted.
3. Phase doc: §5 ticks only for verified items; §6 every criterion ticked only if checked in this run; §8 Stage 7 *Done* (date) with a summary and the phase marked done if every criterion passed; §9 "Stage 7" for decisions; §10 "Stage 7" with the baseline, the findings, the counts after, the live checks and what wasn't checked.
4. server/UPSTREAM.md for every server change (each upstream edit with a `// Madoyomi:` comment; new code under `madoyomi.core.*`; a new table is a new migration `M0076_…` added to `MigrationsFromEmptyDbTest`).
5. docs/PLAN.md: Phase 6's status (done when the exit run passed, with its summary), every Phase 6 milestone ticked only if verified, §6 risks and §7 open decisions if they change, §8 "First tasks" pointing at Phase 7.
6. README.md, matching the phase doc and PLAN.md: the status box, the Phase 6 Roadmap row (✅ when done), the commands tables if a command changes, "Decided so far in Phase 6"; app/README.md for the code changes.
7. Write the ready-to-paste prompt for the next stage (Phase 7's first stage, from docs/phases/phase-7-packaging-release.md; plan its stages with me first if the doc has no stage plan) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main after I have merged Stage 7; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 696 + 17 skipped, plus any new tests). The live spec ≥ 17/17 with ≥ 109 documents. `.\gradlew.bat build` ≥ 853 green, smoke 24/24. If the shell changed: `cargo test` ≥ 18, clippy and fmt clean.
- The Phase 6 doc has Stage 7 *Done* in §8 (only once its live checks passed), §9 "Stage 7" and §10 "Stage 7"; §5 and §6 tick only verified items.
- docs/PLAN.md: Phase 6's status matches the phase doc; only verified milestones ticked; §8 pointing at Phase 7.
- README.md's status box, Roadmap row and "Decided so far in Phase 6" match the Phase 6 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment) and the migrations.
- NEXT-PROMPT.md holds the next stage's prompt.
- `git status`: on feat/phase-6-exit-run, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv/ffmpeg/yt-dlp binaries, backup files (`.madoyomi-backup`, `.tachibk`, `.proto.gz`), video, subtitle or HLS segment files, downloaded episodes or chapters, CBZ files;
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
