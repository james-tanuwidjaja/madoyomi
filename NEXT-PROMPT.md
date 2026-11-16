Start Phase 6 (docs/phases/phase-6-library-features.md) **Stage 6: backup and restore** (steps 15–17).

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 6, its milestones, §6 risks, §7 open decisions, §8 "First tasks");
- the Phase 6 doc: §4 steps 15–17, §5 (Backup & restore, Tests: "Backup round-trip test"), §6 exit criteria (backup → fresh install → restore gives back the same library, categories, progress and settings), §7 risks (no secrets in plain text), §8 (the backup format decision and the Stage 6 line), §9 "Stage 1" (the format: gzipped protobuf, `.madoyomi-backup`), §9 "Stage 5" and §10 "Stage 5";
- server/UPSTREAM.md (every section that added Madoyomi tables or settings: "Repo manager", "Anime sources", "Playback", "Manga reader", "Library categories", "History and badges", "Library updates", "Manga downloads", "Anime downloads"; and "Database migrations");
- README.md (the status box, the Roadmap row, "Decided so far in Phase 6") and app/README.md;
- the code: upstream's `suwayomi.tachidesk.manga.impl.backup.proto` (`ProtoBackupExport`, `ProtoBackupImport`, `ProtoBackupValidator`, `models/Backup*.kt`, `handlers`) and its GraphQL (`BackupMutation`, `createBackup`, `restoreBackup`, `validateBackup`, `restoreStatus`); Madoyomi's tables and settings files (`madoyomi.core.anime.model.table.*`, `AnimeCategoryTable`, `AnimeHistoryTable`, `TrustedSignatureTable`, the `EXTENSIONSTORE` columns from M0069, `EpisodeDownloadTable`, `player-settings.json`, `reader-settings.json`, `library-update.json`, `downloads.json`); in the app Settings (`views/SettingsView.vue`).

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-16, Phase 6 Stages 1–5 done, merged by me before you start):
- Stages 1–4: categories (one list per kind), history and badges, the update checker (off by default), manga downloads (one queue for both kinds, CBZ read offline).
- **Stage 5, anime downloads:** episodes join Stage 4's queue (`AnimeEpisodeDownloader`): the video resolved right before by the player settings' preference, its kind read from the first answer; a direct file streamed with its headers and resumed by range; HLS through `ffmpeg -headers … -c copy` into MKV (yt-dlp when ffmpeg fails, both failures named), the headers quoted for Windows (`ProcessArgs`); subtitles and the source's external audio tracks saved beside the file; files at `downloads/anime/<source>/<title>/<episode>.<ext>`, recorded in `EPISODE_DOWNLOAD` (M0075). mpv plays a downloaded episode in Phase 4's session (`playDownloadedEpisode`), offline too. ffmpeg and yt-dlp found as mpv is (Settings → Downloads, `MADOYOMI_FFMPEG`/`MADOYOMI_YTDLP` from the shell, PATH). No upstream edit.
  - **Verified live** in `pnpm tauri dev` on `p6-ui`: Stage 4's CBZ fix rechecked first (no `ClosedChannelException`); "Oshi no Ko" Ep. 2 (KickAssAnime, HLS) downloaded, paused, resumed, cut off by a restart, then played offline (a dead SOCKS proxy for the JVM) from its file with subtitles and audio, marked watched. The check found that KickAssAnime's audio comes as separate tracks (the first download had none); fixed in the stage.
  - The "Download queue (manga CBZ, anime incl. HLS)" milestone and §6's offline criterion are ticked (the exit run checks them again).
- **My decisions so far for backups (§8–§9):** **gzipped protobuf, `.madoyomi-backup`**: upstream's manga backup (`BackupManga` with its categories, history and meta, which already carries the per-manga reader settings) is reused and extended with anime, repos, trusted certificates and settings, keeping the field numbers compatible so the Aniyomi/Mihon import (the stretch) stays cheap. The extension keeps it apart from a Mihon/Aniyomi `.tachibk`.
- **Upstream's own automated backup runs:** `server.conf` has `server.backupInterval = 1` (days, the default), `backupTTL = 14`, and `p6-ui\backups\` holds 7 `app.madoyomi.auto.admin_*.tachibk` files from it (`ProtoBackupExport.scheduleAutomatedBackupTask`). Decide (and tell me) what happens to it, as Stage 3 did with upstream's update schedule: a second, manga-only backup schedule beside Madoyomi's is likely unwanted.
- Baseline counts (main after the merge; rerun them as task 0's baseline and record them in phase-6 §10 "Stage 6"):
  - server: `.\gradlew.bat build` **847** tests (core 703 with 8 skipped, AndroidCompat 7, ext-host 8, api 129); smoke-api.ps1 -NoBuild **24/24**;
  - app: 69 files (68 + 1 skipped), **680 passed + 16 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **16/16** with **100** documents (it now downloads two whole episodes at 360p: about 8 minutes);
  - `cargo test` **18**; clippy and fmt clean.

Gotchas (Phase 5's and Phase 6 Stages 1–5's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 522; numbers are per name, so check `ls queue | tail` before picking one). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written.
  - Deleting files from the VM is off by default (`rm` fails with "Operation not permitted"); leave scratch files in the gitignored tools/dev-data.
  - Git runs there too. The branch switch is a job with `cmd /c "git switch main 2>&1"`, `git pull`, `git switch -c …`; copy queue\480-branch.ps1 with the new branch name.
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
  - Settings stores have a `fileOverride` and `reload()` for tests (`DownloadSettingsStore`, `ReaderSettingsStore`, `PlayerSettingsStore`, `LibraryUpdateSettingsStore`); put them back in `@AfterEach`.
  - Madoyomi resolvers get the user from `env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()` (see `HistoryGraphQL`).
- A server change needs an entry in server/UPSTREAM.md for every upstream edit, with a `// Madoyomi:` comment at the edit; new Madoyomi code goes under `madoyomi.core.*`. A new table is a new migration (`M0076_…`, in upstream's migration package; see "Database migrations" and M0072–M0075; add its tables to `MigrationsFromEmptyDbTest`'s `NEW_COLUMNS`/`NEW_FOREIGN_KEYS`, and bump its migration count). New GraphQL operations in the app go into `api/operations/` and the live spec, which validates every exported `*_QUERY`/`*_MUTATION`/`*_SUBSCRIPTION` against the schema (100 documents now). **Upstream's GraphQL can differ from its REST routes**, and upstream may run more than you expect (its own scheduled backup, above): check what the GraphQL path really does before relying on upstream behaviour.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR`; wait for "healthy after" in <dataDir>/logs/sidecar.log; close with app-stop.ps1; kill only the pid from sidecar.log. One server per data dir. The app runs the newest `server/api/build/Madoyomi-Server-*.jar`: rebuild it (the 394-style job) before a live check of a server change. A hot reload remounts the page you are on. Pre-bundle any new lazily imported dependency in vite.config.ts.
- **Data dirs** (`tools\dev-data\agent\<name>`, gitignored):
  - a fresh one starts with no repos and no extensions; the webview's localStorage is per user profile, not per data dir (so a "fresh install" check keeps the device's own settings: say which settings are the backend's and which the device's);
  - `seed-live.ps1` seeds a data dir and runs the live spec;
  - `live-data` has AnimeGG, KickAssAnime, MangaPill, MangaDex and Webtoons.com installed;
  - `p6-ui` has 6 anime and 7 manga in the library (anime ids 1–4 and 6, "Oshi no Ko" id 1 with 11 episodes, KickAssAnime), anime categories (Watching, Dropped, Later, Live Renamed, Favorites) and manga ones (Reading, …), history, update marks, Kingdom's Chapters 1–9 downloaded (manga 51, MangaPill) and "Oshi no Ko" Ep. 2 downloaded (with its subtitles and audio; watched, history at 21:40), player settings at the defaults, `downloads.json` at the defaults, the update schedule off. A good source for the round trip: back it up, restore into a fresh data dir, compare;
  - **the offline check:** with the app stopped, set in `<data dir>\server.conf`: `server.socksProxyEnabled = true`, `server.socksProxyHost = "127.0.0.1"`, `server.socksProxyPort = "9"`; put them back (`false`, `""`, `""`) afterwards. `p6-ui`'s are back to the defaults.
- Computer use:
  - resolve the app by D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe while it runs (not by name); **don't call open_application on it**. Run front.ps1 through the runner, then screenshot (a click while the desktop is in front fails).
  - At scale 0.5 the coordinate frame is 1456×819; read button positions from a full screenshot, not from a zoom. The Claude window floats over the top right and takes the keyboard focus after each message: click inside the app before a key.
  - A native `<select>`'s list is masked: pick an option by typing its first letters and Return. A native `<select>` that acts on change ("Download…"): open it with a click first, then type and Return. Windows' notification popups and file dialogs may be masked: ask me what showed.
  - In a list the row's checkbox is small (about 222, y+6 from the row's title at full scale); a click a few pixels off opens the item instead. mpv's window is masked: drive it with ipcdo.ps1.
  - Windows drops injected input while an elevated window is in front. The connection can drop and lose the app grant: request access again. Release the lock at the end.
- Don't read files outside the repo and its tools: such reads are refused.
- The token must stay out of logs and URLs: check that `token=` doesn't reach sidecar.log, madoyomi.log or the dev output. A backup must not carry the API token (it isn't stored anyway) and, per §7, no secret in plain text where avoidable.

Tasks:
0. Branch: once I have merged Stage 5, switch to a new branch off an up-to-date main (a job like queue\480-branch.ps1: `git switch main`, `git pull`, `git switch -c feat/phase-6-backup`). If Stage 5 isn't merged yet (main has no `M0075_AnimeDownloads.kt`), stop and tell me. Then run the whole baseline (a 482-style job) **before writing anything into app/**, and record it.
1. Plan Stage 6 briefly (read upstream's backup code and what its GraphQL path does first), then build it:
   - **the schema** (step 15): `.madoyomi-backup`, gzipped protobuf. Upstream's `Backup` with its `BackupManga` (categories, chapters, history, meta: the per-manga reader settings), upstream's manga categories and sources, kept as they are; Madoyomi's additions in **new field numbers** that Mihon/Aniyomi don't use (check theirs: Aniyomi's anime backup uses its own numbers, which the stretch import has to read): the anime library (entries with seasons, episodes with watched/bookmark/positions, the anime categories and their meta, `ANIME_HISTORY`), the repos (URL, kind, format, the signing key and whether it is trusted), the trusted certificates (`TRUSTED_SIGNATURE`, USER ones), the installed extensions (package, kind, repo; not the APKs), each source's preferences, and the backend's settings files (player, reader, library update, downloads; not the downloads themselves, nor the page cache). Say in §9 what is left out and why (e.g. the update marks, the download queue, the device's localStorage);
   - **manual and scheduled backups** (step 16): `createMadoyomiBackup` (a file in `<data dir>/backups`, or the bytes for a download through a REST route like upstream's `/api/v1/backup/export/file`), a schedule (off / daily / weekly or so, keep the last N; the default is mine: ask me, and settle what happens to upstream's own daily manga backup at the same time), Settings → Backup with Back up now, the list of backups with their size and date, Show in Explorer, and the schedule;
   - **restore** (step 16): validate first (what the file holds, what is missing: sources or extensions not installed), then **merge or replace** (which one is the default is mine: ask), upstream's manga restore reused for the manga part; the extensions listed in the backup reinstalled from their repos **after a trust prompt** (the repo's or the certificate's, as the app's install flow does), the anime entries and progress mapped by source and URL as upstream maps manga; the restore's progress over a subscription (as upstream's `restoreStatus`);
   - **the Aniyomi/Mihon import** (step 17, stretch): a `.tachibk`/Aniyomi backup's manga (upstream already reads Mihon's) and anime (sources matched by id), if there is time; otherwise noted;
   - the app: Settings → Backup (create, list, restore from one of the list or a file the user picks, the validation's report, merge/replace, the trust prompt for the extensions, the progress), new operations in `api/operations/` and the live spec (a backup created, validated and restored into the live server's data on `live-data` without losing anything: e.g. restore with merge and compare counts).
   Tests: **the round trip** (step 18): a backup of a data dir with both kinds (library, categories with their sort meta, episodes and chapters with marks and positions, history, the reader settings in a manga's meta, repos, trusted certificates, settings) → a fresh database → restore → the same state, compared field by field; merge vs replace; a backup with an extension that isn't installed; a file that isn't a backup (`BAD_REQUEST`); app tests for Settings → Backup.
2. Live in `pnpm tauri dev`: back up `p6-ui`, restore it into a **fresh data dir** (§6's third criterion: the same library, categories, progress and settings; the extensions come back after the trust prompt), and compare (the Library's tabs and counts, a details page's marks, History, Settings). A scheduled backup with a short interval (an env override like Stage 3's `MADOYOMI_UPDATE_INTERVAL_SECONDS`), keeping the last N. Check the token stays out of the logs and the backup. Fix what you find, with tests, and rerun the checks.
3. Phase doc: §5 ticks only for verified items (Backup & restore, "Backup round-trip test"); §6's third criterion only if it was checked from a fresh install; §8 Stage 6 *Done* (date) with a summary; §9 "Stage 6" for decisions and defaults; §10 "Stage 6" with the baseline, the findings, the counts after, the live check and what wasn't checked.
4. server/UPSTREAM.md for every server change (each upstream edit with a `// Madoyomi:` comment; new code under `madoyomi.core.*`; a new table is a new migration `M0076_…` added to `MigrationsFromEmptyDbTest`).
5. docs/PLAN.md: Phase 6's status ("Stages 1–6 of 7 done" with Stage 6's summary); tick "Backup / restore round-trip" only if it is verified; §6 risks (backups: secrets, restoring extensions) and §7 open decisions if they change; §8 "First tasks" pointing at Stage 7 (the exit run).
6. README.md, matching the phase doc and PLAN.md: the status box, the Phase 6 Roadmap row (🟡 with Stages 1–6 done), a Backups bullet, the commands tables if a command changes (e.g. the live spec's description), "Decided so far in Phase 6"; app/README.md for the code changes.
7. Write the ready-to-paste prompt for Stage 7 (the exit run: every §6 criterion from a fresh data dir, the downloads and backups included, and a fix round) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main after I have merged Stage 6; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 680 + 16 skipped, plus the new tests). The live spec ≥ 16/16 with ≥ 100 documents. `.\gradlew.bat build` ≥ 847 green, smoke 24/24. If the shell changed: `cargo test` ≥ 18, clippy and fmt clean.
- The Phase 6 doc has Stage 6 *Done* in §8 (only once its live check passed), §9 "Stage 6" and §10 "Stage 6"; §5 and §6 tick only verified items.
- docs/PLAN.md: Phase 6's status matches the phase doc; only verified milestones ticked; §8 pointing at Stage 7.
- README.md's status box, Roadmap row and "Decided so far in Phase 6" match the Phase 6 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment) and the migrations.
- NEXT-PROMPT.md holds Stage 7's prompt.
- `git status`: on feat/phase-6-backup, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv/ffmpeg/yt-dlp binaries, **backup files (`.madoyomi-backup`, `.tachibk`, `.proto.gz`)**, video, subtitle or HLS segment files, downloaded episodes or chapters, CBZ files;
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
