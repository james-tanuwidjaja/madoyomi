Start Phase 4 (docs/phases/phase-4-playback.md), Stage 1. First read the phase doc and propose its stage plan, as Phase 3 did in its §8 "Stage plan". Then implement Stage 1 of that plan. Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 4 doc, all of it: §1-§8. Its checklist is still empty, and §8 holds two open decisions: mpv in a separate window or embedded (PLAN.md §7), and whether the backend or Tauri owns the mpv process;
- the Phase 0 doc's S4 findings ("S4 (mechanics done …)" and "S3/S4 with a real video", 2026-11-03): the mpv flags that work, `--ytdl=no`, and the named pipe;
- the Phase 2 doc's §9/§10 on videos (`fetchEpisodeVideos`, `fetchHosterVideos`, `resolveEpisodeVideo`, hosters, `Video` headers, subtitle and audio tracks, lib-16 `mpvArgs`/`timestamps`);
- server/UPSTREAM.md: "Startup contract", "API (Phase 1 Stage 7)", "Anime sources" (the video mutations), "Library flag (Phase 3)" and "Id-keyed caches in the data dir";
- the Phase 3 doc's §8 stage plan, §9 decisions (Stages 1-7) and §10 Stage 7 exit run, as an example of a stage plan and of how a stage and an exit run are recorded;
- README.md (the status box, the Roadmap, the decisions, and the NOTE on pnpm tauri dev) and app/README.md (the structure, the routes, the sidecar).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine. The two §8 decisions and the stage plan itself are mine: propose them with a recommendation, and wait for my answer before building on them.

Where things stand (Phases 0-3 done, 2026-11-07; Phase 3 Stage 7, the exit run, on branch feat/phase-3-exit):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar.
  - Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md.
  - The last server change was Phase 3 Stage 7's: the thumbnail and chapter-page caches moved from %TEMP%\Madoyomi into <dataDir>/cache/{thumbnails,manga}. They name their files by database id, and a fresh data dir showed another one's covers.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST is only for binaries. GETs may pass ?token=.
  - Errors carry {code, details}. SOURCE_FAILED means a source's site failed.
  - The anime video API is in place since Phase 2: `fetchEpisodeVideos`, `fetchHosterVideos` and `resolveEpisodeVideo`, which return `Video` with its URL, headers, subtitle and audio tracks. Nothing about playback is stored yet: there is no `episode_progress` table and no player API.
- The shell (app/src-tauri/src/sidecar.rs):
  - runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it);
  - restarts it after a crash (1, 2, 4, 8 s; gives up after 5 failed starts in a row, with Retry);
  - since Stage 7, resolves java and the jar for every start, so Retry finds a jar that was missing at app start;
  - owns the backend through a Job Object, which takes the JVM down when the app dies. Phase 0 S5 noted a tiny gap between spawn and the job assignment. If mpv becomes a child process too, it needs the same care.
- The UI (app/README.md has the structure and the routes):
  - every Phase 3 screen is done: Repos, Extensions with the trust prompt, Browse and the source pages with FilterRenderer, Details for both kinds with a virtualized EpisodeList/ChapterList, the Library, Settings and each source's settings with PreferenceRenderer;
  - episode rows don't act yet. A line above the list says playing comes in Phase 4, and watched marks with Phase 6 (the list's "watched" filter is a placeholder);
  - Phase 4 adds the Play action, the video picker, the resume prompt and the watched state to this list (components/EntryItemList.vue, EpisodeList.vue, views/AnimeDetailsView.vue, composables/useEntries.ts and useDetailsPage.ts).
- PLAN.md: Phase 3 is done, with every milestone ticked. Phase 4's five milestones are open. §7 still has "mpv in a separate window (MVP) vs. embedded" open; Phase 4 decides it.

Baseline (after Phase 3 Stage 7; rerun all of it at the start and record it as this stage's baseline):
- server: `.\gradlew.bat build` is green with 669 tests, 0 failures: core 606 (8 skipped), AndroidCompat 7, ext-host 8, api 48. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Server changes need tests, a rebuild and an UPSTREAM.md entry.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass (on the Stage 7 jar).
- app: `pnpm test` gives 33 files, 256 passed + 9 skipped (the live spec). `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 9/9 against a seeded server, with 31 documents validating.
- app/src-tauri: `cargo test` 15 tests; `cargo clippy --all-targets` and `cargo fmt --check` clean.

Gotchas:
- mpv:
  - it is installed at C:\Program Files\MPV Player\mpv.exe and is on PATH. tools/bin/ has no mpv.exe, although the Phase 4 doc §2 expects one there for dev. Decide where dev and release builds find it (Phase 7 bundles it);
  - spikes/mpv-play/play.ps1 and header-proxy.mjs are the S4 spike: a local proxy that returns 403 without the right Referer/User-Agent. It is a ready test bed for header handling;
  - S4 findings: `--referrer=`/`--user-agent=`, one `--http-header-fields-append="Name: value"` per other header (not the comma-joined form), `--sub-files-append=` per subtitle, `--ytdl=no` always, and an IPC pipe of `\\.\pipe\<name>` with a unique name per session;
  - the Phase 4 doc's step 1 still says "comma-joined" headers. S4 found that form unsafe; fix the doc when you plan.
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 5-6 min).
  - From the Bash tool: `export JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21' && cmd //c ".\\gradlew.bat build --console=plain"` from server/.
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat. `.\gradlew.bat :api:shadowJar` builds only the jar; `build` rebuilds it too.
  - Don't rebuild while a backend runs from that jar (Windows locks it): close the app first.
  - New server code goes under `madoyomi.*`; an upstream file edit needs a `// Madoyomi:` comment and an UPSTREAM.md row.
  - New DB tables are Madoyomi migrations, by the rules in UPSTREAM.md "Database migrations". The highest is M0071_AnimeBrowse, so the next is M0072.
- PowerShell and servers:
  - from the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio, and sets $script:base and $script:token; GqlData runs a query;
  - the Bash tool can't run `powershell` (exit 127): start PowerShell scripts from the PowerShell tool, as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> > <log> 2>&1`, in the background for long runs.
- Seeding a data dir for the live spec: a scratchpad seed.ps1 that
  1. dot-sources tools/lib/MadoyomiServer.ps1;
  2. runs Start-MadoyomiServer with tools\bin\jdk-21\bin\java.exe, (Resolve-MadoyomiJar <repo> '' -NoBuild) and a scratch data dir;
  3. adds yuzono (`https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json`, ANIME) and Keiyoushi (`https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb`, MANGA) with `addExtensionStore(input: {indexUrl, type})`, if `extensionStores` has none of that type;
  4. installs AnimeGG, MangaPill, KickAssAnime and MangaDex with InstallWithProgress, if `extension(pkgName:)` says they aren't installed. The packages are `eu.kanade.tachiyomi.animeextension.en.animegg`, `eu.kanade.tachiyomi.extension.en.mangapill`, `eu.kanade.tachiyomi.animeextension.en.kickassanime` and `eu.kanade.tachiyomi.extension.all.mangadex`;
  5. sets $env:MADOYOMI_LIVE_URL/TOKEN and runs `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts --silent=false 2>&1"` from app/;
  6. runs Stop-MadoyomiServer in a `finally`.

  One server per data dir: H2 locks it.
- Videos for live checks:
  - AnimeGG gives MP4s that need its Referer (Phase 0: the host answers 500 without it);
  - KickAssAnime gives HLS with hosters;
  - Phase 2's compat report (docs/research/anime-compat.md) lists what each of the top 20 returns, with subtitles and audio tracks;
  - video URLs expire: resolve them right before launching.
- Live app checks:
  - run `pnpm tauri dev` in the background (Bash tool, with MADOYOMI_DATA_DIR exported, output to a scratchpad log). Wait on <dataDir>/logs/sidecar.log for a "healthy after" line **from this session** (count the lines first, or use a fresh dir);
  - `tauri dev` rebuilds and restarts the app by itself when src-tauri changes;
  - kill only the pid from sidecar.log ("pid N listening"): other java processes are running;
  - close the app with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow();
  - UI preferences (pins, theme, kind, sorts) live in the WebView's localStorage, so they survive a fresh data dir.
- Screenshots, clicks and typing work through a scratchpad win.ps1 (recreate it):
  - SetProcessDPIAware, the madoyomi MainWindowHandle, ShowWindow(9), SetForegroundWindow, GetWindowRect;
  - `-Action shot -Out x.png` (CopyFromScreen of the rect), `click -X -Y` (SetCursorPos + mouse_event 2/4), `wheel -X -Y -Delta -120 -Count N` (mouse_event 0x0800), `paste -Text` (Set-Clipboard, then SendKeys ^a ^v), and `keys -Text '{ENTER}'`.

  Coordinates are window-relative physical pixels, read off the screenshot (the window is 1296x839). Some positions:
  - the sidebar: Anime x≈77 / Manga x≈178 at y≈101, Library y≈153, Browse 191, Extensions 229, Settings y≈714;
  - the page's actions at y≈72;
  - on a details page: Add to library (520, 176) and Refresh x≈619 (x≈660 when in the library); the first episode row is at y≈413, with rows about 61 px apart.

  An mpv window is a separate process: find it by its own MainWindowHandle to screenshot it. Don't commit screenshots.
- Reka in jsdom:
  - Tabs change on `mousedown`. Select opens with Enter on its trigger, once `scrollIntoView`, `hasPointerCapture` and `releasePointerCapture` are stubbed;
  - Dialogs portal to <body> (mountView attaches there);
  - the virtualizers need clientHeight/clientWidth, offsetHeight and scrollHeight stubbed.
- TypeScript, ESLint and tests:
  - TypeScript 6 loads no @types in app code: there's no `process` (reach it through globalThis), no `node:` types (import them untyped through `as string`), no `replaceAll` or `at` (lib ES2020);
  - Vite refuses a `?raw` import from outside app/;
  - `h()` doesn't take a generic SFC: mount it through `as unknown as Component`;
  - the route smoke test needs an `h1` in `main` on every route without a backend;
  - ESLint reads any method called `sort` as Array.prototype.sort in computeds;
  - `pnpm format` rewrites files: read them again before an Edit;
  - a vitest `--reporter=json` run without a valid --outputFile writes app/.vitest/: delete it, never commit it.
- Shell edits: Bash heredocs with many quotes or backticks fail to parse. Use the Write/Edit tools, or write a scratchpad file and `cat >>` it.
- PowerShell: variables are case-insensitive (`$pid` is read-only: use `$procId`); a function's pipeline output is its return value; PS 5.1 has no `??`; keep .ps1 files ASCII.
- The token must stay out of logs and URLs, except where the contract needs ?token= (img src, WebSocket). If mpv ever fetches through the backend (a proxy fallback), keep the token out of mpv's command line and logs. Check sidecar.log, madoyomi.log, the dev output and any mpv log for `token=` at the end.
- Adding npm packages or Gradle dependencies needs network access; lockfile changes are expected and are text.
- Python and gh are not installed.

Tasks (Phase 4 Stage 1):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-4-<topic>` (the topic of Stage 1, e.g. feat/phase-4-launcher). Never edit on main. (If feat/phase-3-exit isn't merged yet, stop and tell me.)
1. Baseline: rerun everything in "Baseline" (smoke and the live spec included) and record it.
2. Read the Phase 4 doc and propose:
   - its stage plan (each stage ending in something verifiable, as Phase 3's §8);
   - the two §8 decisions, with a recommendation each;
   - where mpv comes from in dev and release.

   Ask me, then write the agreed plan into the Phase 4 doc's §8 and the decisions into §9 (create §9/§10 as in Phase 3). Fix the doc's step 1 header form to match S4's findings.
3. Implement Stage 1 of the agreed plan, with its tests (server and/or app, as the stage needs), and verify it live in `pnpm tauri dev` if the stage reaches the UI.
4. Phase doc: §5 ticks (only verified items), §8 (stage 1 *Done* with a summary), §9 decisions, and §10 findings, including what wasn't checked live and why.
5. docs/PLAN.md: Phase 4 in progress, with a short status like Phase 3's ("Stage 1 of N done"). Tick a milestone only if every phase-doc item behind it is verified. Update §8 "First tasks", §6 risks and §7 open decisions: close the mpv window question with the decision.
6. README.md: the status box, the Roadmap row for Phase 4 (🟡 In progress), the commands tables for any new or changed command, a "Decided so far in Phase 4" list, and the NOTE on pnpm tauri dev if mpv changes what it needs. Make it match the phase doc and PLAN.md. Update app/README.md if the structure or routes change, and server/UPSTREAM.md for any server change.
7. At the end, write the ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 256 tests (the live spec skipped);
  - the live spec passes 9/9 (or more) against a seeded server;
  - `cargo build`, `cargo test` (at least 15) and `cargo clippy --all-targets` pass.
- If anything in server/ changed: `.\gradlew.bat build` is green (at least 669 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change (new tables as migrations).
- The Phase 4 doc has the agreed stage plan (§8) and decisions (§9), and its §5 ticks are only verified items.
- docs/PLAN.md: Phase 4's milestones are ticked if and only if their phase-doc items are verified, §4/§8 show Phase 4 in progress, and §7 matches the decision taken.
- README.md's status box, Roadmap row and decisions match the Phase 4 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on the feat/phase-4-… branch, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files, mpv binaries or video files;
  - hs_err logs, heap dumps, screenshots;
  - node_modules, target/, dist/, app/.vitest/, .env.local;
  - tools/compat-test/out, tools/dev-data, spikes/mpv-play/out.

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
