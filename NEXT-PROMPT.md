Start Phase 6 (docs/phases/phase-6-library-features.md) **Stage 5: anime downloads** (step 11, and steps 12–14 for anime). First recheck one fix from Stage 4 in the app (task 1).

Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md (Phase 6, its milestones, §6 risks, §7 open decisions, §8 "First tasks");
- the Phase 6 doc: §4 steps 11–14, §5 (Downloads: the three anime items, "Player/reader use local files" ticked for manga), §6 exit criteria (the HLS episode opening offline), §7 risks (HLS with expiring or encrypted segments), §8 (the ffmpeg/yt-dlp decision and the Stage 5 line), §9 "Stage 1" (the download tool) and "Stage 4", §10 "Stage 4" (with its "Live check" and "Not checked");
- server/UPSTREAM.md "Manga downloads (Phase 6)" and "Playback (Phase 4)";
- README.md (the status box, the Roadmap row, the Downloads bullets, "Decided so far in Phase 6") and app/README.md;
- the code: `madoyomi.core.downloads`, `DownloadGraphQL`, `madoyomi.core.playback`, `LibraryUpdater.onNewItems`; in the app `useDownloads`, `utils/downloads.ts`, `views/DownloadsView.vue`, `components/ChapterList.vue` and `EpisodeList.vue`, Settings → Downloads in `views/SettingsView.vue`.

Never commit. Tick checklist items only once they are done and verified. Ask me only about decisions that are genuinely mine.

Where things stand (2026-11-16, Phase 6 Stages 1–4 done, merged by me before you start):
- Stages 1–3: categories (one list per kind), history and badges, the update checker (off by default).
- **Stage 4, manga downloads:** Madoyomi's own download queue for both kinds (`madoyomi.core.downloads`, `M0074_Downloads`' `DOWNLOAD` table with nullable chapter/episode references), in place of upstream's in-memory `DownloadManager`.
  - **The queue:** it survives a restart (order, pauses, failures; a row cut off while DOWNLOADING is queued again). At most 3 at once and 1 per source (`<data dir>/downloads.json`), two of a source 1 s apart; two retries (5 s, 30 s), then FAILED with Retry; pause, resume, cancel, reorder; per-kind `ItemDownloader`s.
  - **Manga:** pages staged in `<downloads>/.partial/manga/<chapterId>/`, then `ComicInfo.xml` and the CBZ (`.part`, then moved) at `downloads/manga/<source>/<title>/<chapter>.cbz`, read by upstream's page route, offline too. Auto-download after an update check (off by default; the Categories dialog's Downloads flag).
  - **Upstream edits:** three: `ApplicationDirs.mangaDownloadsRoot`, `Chapter.downloadNewChapters` (upstream's auto-download off), and `ArchiveProvider.getImageInputStream` (the live check's fix below).
  - **The app:** the Downloads route (follows the anime/manga switch; anime shows a note until now), the sidebar's count, the chapter list's actions, "↓ N" on the cards, Settings → Downloads.
  - **Verified live** in `pnpm tauri dev` on `p6-ui`, including two restarts mid-download and reading downloaded chapters with the sources cut off by a dead SOCKS proxy (paged and webtoon). The check found two problems:
    - a CBZ page cut off by `ClosedChannelException`: upstream dropped the page's `ZipFile`, whose finalizer closed the file mid-page. The fix makes the page's stream own its archive (`CbzPageStreamTest`, which fails on upstream's code). **It wasn't rechecked in the app** (the session ended): task 1;
    - "Nothing downloaded yet." after Clear list; now worded right.
- **My decisions so far for downloads (§8–§9):** anime downloads use **ffmpeg for HLS, with yt-dlp as the fallback** when ffmpeg fails (the failure reported); direct files are downloaded by the backend with the source's headers; both tools bundled in Phase 7. Auto-download off on a fresh install; downloads in the data dir (`downloads/anime` beside `downloads/manga`; a folder of my choice in Phase 7); the Downloads screen follows the anime/manga switch.
- PLAN.md's "Download queue (manga CBZ, anime incl. HLS)" milestone is unticked: Stage 5 ticks it once the anime half is verified.
- Baseline counts (main after the merge; rerun them as task 0's baseline and record them in phase-6 §10 "Stage 5"):
  - server: `.\gradlew.bat build` **836** tests (core 698 with 8 skipped, AndroidCompat 7, ext-host 8, api 123); smoke-api.ps1 -NoBuild **24/24**;
  - app: 69 files (68 + 1 skipped), **671 passed + 15 skipped**; lint, typecheck, format:check and build clean;
  - the live spec **15/15** with 98 documents;
  - `cargo test` **17**; clippy and fmt clean.

Gotchas (Phase 5's and Phase 6 Stages 1–4's still apply):
- If your shell is a Linux VM (device_bash) rather than Windows, the Windows side runs through the agent job runner: ask me to start tools\dev-data\agent\start-runner.cmd (check runner.heartbeat is fresh).
  - Each queue\NNN-name.ps1 runs once, hidden; its output is queue\NNN-name.log and its exit code queue\NNN-name.done (the last job was 479). **Jobs run in parallel**: wait for one's .done before queueing the next if they share anything. Write a job to a temp name and rename it to .ps1, or the runner may take it half-written.
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
  - **Flaky: `LooperTest` failed once at LooperTest.kt:78 (Stage 4's baseline); `CefTest` fails with `IOException: proxy.example` or `IOException: bad host` when `SocksProxyTest`'s proxy leaks; rerun the build.** `ExtensionThreadsTest` may skip one more case (its pool-size assumption).
  - ktlint wants a blank line before a commented declaration; keep lines under 140 characters (a KDoc line too). ktlintFormat rewrites files on the Windows side, so re-read a file before editing it again.
  - A single class: `.\gradlew.bat :api:test --tests <fqcn>`.
  - API tests extend `ApiGraphQLTest` (a fresh in-memory H2 per test, every migration run) or `AnimeApiTest` (`register(FakeAnimeSource)`, `animeRow`, `episodeRow`). A manga row needs `url`, `title`, `sourceReference` and `memo`, with `initialized = true` so upstream doesn't queue a fetch (see `MangaNewEntryCategoriesApiTest`, `HistoryApiTest`).
  - A fake manga source is a `Source` registered with `GetSource.registerSource(id to source)`; see `FakeMangaSource` in `LibraryUpdateApiTest`.
  - The test config allows fewer parallel sources than the default, and anime and manga ids are separate sequences (both start at 1).
  - A GraphQL `Long` is the `LongString` scalar: pass `"5000"`, not `5000`, in a test's literal.
  - `ApiGraphQLTest` classes have **one instance for all their tests** (its `@BeforeAll` isn't static): a fake held in a property carries over between tests, so make it in `@BeforeEach` (Stage 4's `DownloadQueueApiTest`).
  - Stage 4's download tests: `DownloadQueue.downloaders[kind]` takes a fake `ItemDownloader`; `DownloadQueue.stop()`/`start()` stand for a restart; set `staggerMs`/`retryDelaysMs` low and put them back; `awaitIdle` also waits on DOWNLOADING rows (the scheduler claims a row just before its job runs). A test iterating a `Collections.synchronizedList` that a collector fills must hold its lock (a Stage 3 test flaked on that).
  - Madoyomi resolvers get the user from `env.graphQlContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()` (see `HistoryGraphQL`).
- A server change needs an entry in server/UPSTREAM.md for every upstream edit, with a `// Madoyomi:` comment at the edit; new Madoyomi code goes under `madoyomi.core.*`. A new table is a new migration (`M0075_…`, in upstream's migration package; see "Database migrations", and M0072–M0074; add its tables to `MigrationsFromEmptyDbTest`'s `NEW_COLUMNS`/`NEW_FOREIGN_KEYS`). New GraphQL operations in the app go into `api/operations/` and the live spec, which validates every document against the schema (98 documents now). **Upstream's GraphQL can differ from its REST routes** (Stage 1's new-entry flag), and upstream may run more than you expect (Stage 3: its own scheduled manga update was running): check what the GraphQL path really does before relying on upstream behaviour.
- Live app checks: `pnpm tauri dev` from app/ with `$env:MADOYOMI_DATA_DIR`; wait for "healthy after" in <dataDir>/logs/sidecar.log; close with app-stop.ps1; kill only the pid from sidecar.log. One server per data dir. The app runs the newest `server/api/build/Madoyomi-Server-*.jar`: rebuild it (the 394-style job) before a live check of a server change. A hot reload remounts the page you are on. Pre-bundle any new lazily imported dependency in vite.config.ts.
- **Data dirs** (`tools\dev-data\agent\<name>`, gitignored):
  - a fresh one starts with no repos and no extensions; the webview's localStorage is per user profile, not per data dir;
  - `seed-live.ps1` seeds a data dir and runs the live spec;
  - `live-data` has AnimeGG, KickAssAnime, MangaPill, MangaDex and Webtoons.com installed;
  - `p6-ui` has 6 anime and 7 manga in the library (anime ids 1–4 and 6, "Oshi no Ko" id 1 with 11 episodes). Kingdom (manga 51, MangaPill, 928 chapters, in Reading) is the one manga with chapters: the six MangaDex entries have none and fail "No chapters found";
  - Stage 3 left `p6-ui`'s update schedule off (`library-update.json`). Stage 4's live check left Kingdom's Chapters 1–9 downloaded (`p6-ui\downloads\manga\MangaPill (EN)\Kingdom\`, the completed list cleared), Chapters 2 and 8 read, `downloads.json` at the defaults (3, 1, auto-download off) and Kingdom's chapter sort descending, its reader mode Single. `server.conf.p6-ui.bak` in tools\dev-data\agent is the clean `server.conf`.
  - **The offline check (used in Stage 4, it works): a SOCKS proxy to a closed port for the JVM, no admin rights needed.** With the app stopped, set in `<data dir>\server.conf`: `server.socksProxyEnabled = true`, `server.socksProxyHost = "127.0.0.1"`, `server.socksProxyPort = "9"` (upstream sets Java's `socksProxyHost`/`socksProxyPort` system properties from them, ServerSetup.kt ~line 430; Java leaves localhost alone, so the app still reaches the backend). Start the app, check in madoyomi.log that a source request fails (e.g. a chapter not downloaded fails to open), then read the downloaded one (Stage 4: `SOURCE_FAILED Connection refused: getsockopt`). It also cuts off mpv only if mpv goes through the JVM: mpv is its own process, so for Stage 5's offline playback make sure the local file is played, not the stream (check mpv's command line or log). Put the three values back (`false`, `""`, `""`) afterwards. Turning off the PC's network would also cut the session.
- Stage 4's download code to build on: `madoyomi.core.downloads` (`DownloadQueue`, `ItemDownloader` per `ExtensionKind`, `MangaChapterDownloader`, `CbzWriter`, `MangaDownloads`, `DownloadSettingsStore`), `DownloadGraphQL` (`enqueueDownloads` refuses the anime kind with `BAD_REQUEST` until Stage 5), the app's `useDownloads`, `utils/downloads.ts`, `DownloadsView` (its anime view is a note now, `anime-note`), `ChapterList`'s actions (`EpisodeList` gets the same). Staging goes under `<downloads>/.partial/<kind>/<itemId>/`; a finished file is written as `.part` then moved.
- Phase 4's playback to build on: `madoyomi.core.playback` (mpv launched by the backend with the video's headers in a per-session include file, the IPC client, progress saves), the video picker and the player settings (preferred quality and server, `PlayerSettings`), `fetchEpisodeVideos`/`resolveEpisodeVideo` (the videos resolved through the extension). A downloaded episode should play through the same session (progress, watched mark, next episode) with a local path in place of the URL.
- Tools: ffmpeg and yt-dlp go in `tools/bin/` (gitignored, Phase 7 bundles them; check what is there and how Phase 4 found mpv, `MADOYOMI_MPV`/the player settings' path). Never commit them, nor downloaded video.
- Computer use:
  - resolve the app by D:\Projects\madoyomi\app\src-tauri\target\debug\madoyomi.exe while it runs (not by name); **don't call open_application on it**. Run front.ps1 through the runner, then screenshot (a click while the desktop is in front fails).
  - At scale 0.5 the coordinate frame is 1456×819; read button positions from a full screenshot, not from a zoom. The Claude window floats over the top right and takes the keyboard focus after each message: click inside the app before a key.
  - A native `<select>`'s list is masked: pick an option by typing its first letters and Return. Windows' notification popups can't be granted: ask me whether a toast showed. mpv's window is masked: stop it with ipcdo.ps1 `-Quit` through the runner.
  - In a chapter or episode list the row's checkbox is small (about 222, y+6 from the row's title at full scale); a click a few pixels off opens the item instead. A native `<select>` that acts on change (the chapter list's "Download…"): open it with a click first, then type and Return, or the first letter alone fires the first option.
  - In the reader the toolbar hides while paging and a click on the left or right third turns a page: click the middle to show the toolbar before "← <manga>".
  - In the webtoon mode End jumps to the strip's end (the wheel is slow on long chapters). "← <manga>" goes back in history (from the Downloads screen's Read, back to Downloads).
  - Show in Explorer's window showed in screenshots without a grant in Stage 4; close it through a job: `(New-Object -ComObject Shell.Application).Windows() | ? { $_.LocationURL -like "*p6-ui*" } | % { $_.Quit() }` (queue\466-closeexp.ps1).
  - Windows drops injected input while an elevated window is in front. The connection can drop and lose the app grant: request access again. Release the lock at the end.
- Don't read files outside the repo and its tools (e.g. Windows' own notification store): such reads are refused.
- The token must stay out of logs and URLs: check that `token=` doesn't reach sidecar.log, madoyomi.log or the dev output.


Tasks:
0. Branch: once I have merged Stage 4, switch to a new branch off an up-to-date main (a job like queue\419-branch.ps1: `git switch main`, `git pull`, `git switch -c feat/phase-6-anime-downloads`). If Stage 4 isn't merged yet (main has no `M0074_Downloads.kt`), stop and tell me. Then run the whole baseline (a 420-style job) **before writing anything into app/**, and record it.
1. **Recheck Stage 4's CBZ fix in the app** (before any Stage 5 change; the jar from the baseline has it): start `pnpm tauri dev` on `p6-ui`, open Kingdom's downloaded chapters (1–9) in the webtoon mode and jump through them (End, Home, End; at least three chapters, Chapter 4's large PNG pages among them), then check that `madoyomi.log` has no `ClosedChannelException` since the start. Put the mode back to Single. Record it in phase-6 §10 "Stage 4" (drop it from that stage's "Not checked") and §10 "Stage 5".
2. Plan Stage 5 briefly, then build it:
   - **the anime downloader**, an `ItemDownloader` for `ExtensionKind.ANIME` joining Stage 4's queue (same limits, retries, pause, resume, cancel, reorder, restart): the episode's videos resolved right before the download (through the extension, as Play does), the video picked by the player settings' preferred quality and server (the source's pick otherwise); `enqueueDownloads` accepting the anime kind;
   - **direct files** (MP4, MKV, …) streamed by the backend with the video's headers, resumable from the staged part where the server allows a range; **HLS** through `ffmpeg -headers … -i <m3u8> -c copy` (subtitles muxed or saved beside it), and **yt-dlp** (`--add-header`) when ffmpeg fails, with both failures in the error; DASH if a source in `live-data` needs it, otherwise noted;
   - files at `downloads/anime/<source>/<title>/<episode>.<ext>` with the subtitle files beside them, staged in `<downloads>/.partial/anime/<episodeId>/` and moved when complete; progress in bytes (and segments/time for HLS) in the queue rows;
   - the tools found in `tools/bin/` (or settings/env, as mpv), with a clear error when missing; never committed;
   - **mpv plays the local file** for a downloaded episode (the same session: progress saves, the watched mark, the next episode), with its subtitles; delete and the episode list's "Downloaded" mark;
   - the anime auto-download through `LibraryUpdater.onNewItems` (the setting and the Categories dialog's Downloads flag, as manga);
   - the app: the Downloads screen's anime view (the queue and the completed list, Read becomes Play), the episode list's actions (Download per row with its state and Cancel/Retry, "Downloaded", the selection's Download and Delete downloads, "Download…" next N unwatched), "↓ N" on anime cards, Settings → Downloads (the tools' paths or status if needed);
   - new GraphQL in `api/operations/` and the live spec (it validates every document): an anime episode downloaded through the queue on `live-data` (an HLS one, KickAssAnime, and a direct one, AnimeGG), followed over the WebSocket, deleted.
   Tests: the anime downloader on a loopback host (a direct file with a header check and a resumed range, HLS through ffmpeg from a generated playlist, the yt-dlp fallback with ffmpeg made to fail, subtitles beside the file), the queue with anime items, the local playback path; app tests for the anime view and the episode list's actions.
3. Live in `pnpm tauri dev` (`p6-ui`: "Oshi no Ko" id 1, KickAssAnime, HLS): download an episode from its list, watch the queue, pause/resume it, restart the app mid-download, then **play it offline** in mpv (the dead SOCKS proxy for the JVM, and check mpv opened the local file), with subtitles; a direct-file episode (AnimeGG) too if one is in `p6-ui` or `live-data`. Check the token stays out of the logs. Fix what you find, with tests, and rerun the checks.
4. Phase doc: §5 ticks only for verified items (the three anime items, "Player/reader use local files" now for mpv too); §6's "downloaded anime episode (HLS) and manga chapter (CBZ) both open offline" only if both were checked offline; §8 Stage 5 *Done* (date) with a summary; §9 "Stage 5" for decisions and defaults; §10 "Stage 5" with the baseline, the findings, the counts after, the live check and what wasn't checked.
5. server/UPSTREAM.md for every server change (each upstream edit with a `// Madoyomi:` comment; new code under `madoyomi.core.*`; a new table is a new migration `M0075_…` added to `MigrationsFromEmptyDbTest`).
6. docs/PLAN.md: Phase 6's status ("Stages 1–5 of 7 done" with Stage 5's summary); tick "Download queue (manga CBZ, anime incl. HLS)" only if the anime half is verified; §6 risks (HLS, the tools) and §7 open decisions if they change; §8 "First tasks" pointing at Stage 6 (backup and restore).
7. README.md, matching the phase doc and PLAN.md: the status box, the Phase 6 Roadmap row (🟡 with Stages 1–5 done), the Downloads bullets for anime, the commands tables if a command changes (e.g. the live spec's description), "Decided so far in Phase 6"; app/README.md for the code changes.
8. Write the ready-to-paste prompt for Stage 6 (backup and restore: steps 15–17; the `.madoyomi-backup` gzipped protobuf schema reusing upstream's `BackupManga` with anime, repos, trusted certificates and settings added and the field numbers kept compatible; manual and scheduled backups keeping the last N; restore with merge or replace and the extensions reinstalled after a trust prompt; the Aniyomi/Mihon import as the stretch; the round-trip test) into NEXT-PROMPT.md (overwrite it) and repeat it in your final message. Same format: current state, baseline test counts, gotchas, the tasks (task 0 the branch switch off an up-to-date main after I have merged Stage 5; the README status and PLAN.md tick tasks), verification steps (including the README and PLAN.md checks), and these standing rules.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm build` and `pnpm test` pass (≥ 671 + 15 skipped, plus the new tests). The live spec ≥ 15/15 with ≥ 98 documents. `.\gradlew.bat build` ≥ 836 green, smoke 24/24. If the shell changed: `cargo test` ≥ 17, clippy and fmt clean.
- The Phase 6 doc has Stage 4's CBZ recheck recorded, Stage 5 *Done* in §8 (only once its live check passed), §9 "Stage 5" and §10 "Stage 5"; §5 and §6 tick only verified items.
- docs/PLAN.md: Phase 6's status matches the phase doc; only verified milestones ticked; §8 pointing at Stage 6.
- README.md's status box, Roadmap row and "Decided so far in Phase 6" match the Phase 6 doc and PLAN.md.
- server/UPSTREAM.md lists every upstream edit (each with a `// Madoyomi:` comment) and the migrations.
- NEXT-PROMPT.md holds Stage 6's prompt.
- `git status`: on feat/phase-6-anime-downloads, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv/ffmpeg/yt-dlp binaries, **video, subtitle or HLS segment files, downloaded episodes or chapters, CBZ files**;
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
