Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 7: the exit run (steps 22-23 and §6; the remaining §5 items "Component + client tests" and "`pnpm lint`, `pnpm typecheck`, `pnpm test` pass", every §6 exit criterion from a fresh data dir, a fix round, and Phase 3 closed in PLAN.md and README.md). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 3 doc, all of it: §4 steps (22, 23), §5 checklist, §6 exit criteria, §8 stage plan, §9 decisions (Stages 1-6), §10 findings (Stages 1-6, especially each stage's "Not checked live" list);
- server/UPSTREAM.md: "Startup contract" and "API (Phase 1 Stage 7)";
- the Phase 1 doc's §10 "exit run" and the Phase 2 doc's exit stage, as examples of how an exit run was recorded;
- README.md (the status box, the Roadmap, the decisions, and the NOTE on pnpm tauri dev);
- app/README.md (the structure, the routes, the live operation check).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stages 1-6 done; Stage 6 on branch feat/phase-3-settings):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar.
  - Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md.
  - Stage 6 changed nothing in server/. The last server change was Stage 5's `updateAnime`/`updateAnimes`.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST only for binaries (thumbnails, pages, extension icons). GETs may pass ?token=.
  - Errors carry {code, details}. SOURCE_FAILED means a source's site failed or was unreachable.
- The shell (app/src-tauri/src/sidecar.rs, Stage 1):
  - runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it), and restarts it after a crash (1, 2, 4, 8 s; gives up after 5 failed starts in a row, with Retry);
  - a missing jar (MADOYOMI_SIDECAR_JAR pointing at nothing) shows the failure screen at once, with Retry and Open logs. Stage 1 never clicked those two buttons live: that human check was left for this exit run.
- The UI: every screen is done.
  - The screens:
    - Repos and Extensions, with the trust prompt (Stage 3);
    - Browse and the source pages, with FilterRenderer (Stage 4);
    - Details for both kinds and the Library (Stage 5);
    - Settings and /settings/source/:id, with PreferenceRenderer (Stage 6).
  - app/README.md has the structure and the routes.
  - Stage 6 added:
    - api/operations/preferences.ts (manga reads, then changes);
    - composables/usePreferences.ts (optimistic changes in one mutation scope, rolled back on an error, a refusal reported per position; a change drops the source's browse pages and filters, and the source list is read again after its next browse, see `markSourceChanged` in useSources.ts);
    - components PreferenceRenderer/PreferenceField;
    - stores/theme.ts and an inline theme script in index.html;
    - Settings links on Browse rows (⚙) and the source page.
- §5 still open: "Component + client tests" and "`pnpm lint`, `pnpm typecheck`, `pnpm test` pass" (Quality). PLAN.md's last open Phase 3 milestone is "lint / typecheck / tests pass".
- §6 status:
  - "fresh data dir end to end" open;
  - "Source preferences can be changed in the UI and take effect" ticked in Stage 6 ("Rechecked in the exit run");
  - "Killing the backend recovers" ticked in Stage 1 ("Rechecked in the exit run").
- Decided (Phase 3 §9):
  - Reka UI primitives with our own styles; @tanstack/vue-virtual; dark + light themes; a Fluent-like look; native <select> for a plain list of strings;
  - Stage 5: the library flag on the server; manga refresh in two calls;
  - Stage 6: no server change, with the manga preference quirks worked around in the client (read first; upstream's manga side saves before asking the listener and so can't refuse).

Baseline:
- server: `.\gradlew.bat build` is green with 667 tests, 0 failures: core 604 (9 skipped), AndroidCompat 7, ext-host 8, api 48. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. If the fix round changes server/, rebuild, add tests and list the change in UPSTREAM.md.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass (rerun at the start of Stage 6, on the Stage 5 jar; rerun at the start of this stage).
- app: `pnpm test` gives 30 files, 220 passed + 9 skipped.
  - That's client 15, ws 13, realtime 6, entries 7, preferenceCalls 4, hooks 10, usePreferences 5, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6, TrustDialog 5, VirtualList 2, VirtualGrid 5, EntryItemList 5, FilterRenderer 8, PreferenceRenderer 6, filters 10, entryItems 5, preferences 5, ExtensionsView 11, ReposView 8, BrowseView 7, SourceView 13, AnimeDetailsView 9, MangaDetailsView 6, LibraryView 5, SettingsView 7, SourceSettingsView 5.
  - The 9 skipped are the live spec.
  - `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 9/9. The last three are a filtered search per kind, a refresh + library round trip per kind, and a preference round trip per kind. 31 documents validate.
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 4-6 min).
  - From the Bash tool: `export JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21' && cmd //c ".\\gradlew.bat build --console=plain"` from server/.
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat. `.\gradlew.bat :api:shadowJar` builds only the jar; `build` rebuilds the jar too. `pnpm tauri dev` and the seed script use the newest jar.
  - New server code goes under `madoyomi.*` in `api`; an upstream file edit needs a `// Madoyomi:` comment and an UPSTREAM.md row.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio. It sets $script:base and $script:token, and GqlData runs a query.
- Seeding a data dir for the live spec (the Stage 6 recipe): a scratchpad seed.ps1, run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> > <log> 2>&1`:
  1. dot-source tools/lib/MadoyomiServer.ps1;
  2. `Start-MadoyomiServer <java> (Resolve-MadoyomiJar <repo> '' -NoBuild) <scratch data dir>`;
  3. add both repos with GqlData (`addExtensionStore(input: {indexUrl, type})`, if `extensionStores` has none of that type): yuzono `https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json` (ANIME), Keiyoushi `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb` (MANGA);
  4. install AnimeGG, MangaPill, KickAssAnime and MangaDex with InstallWithProgress. The packages are `eu.kanade.tachiyomi.animeextension.en.animegg`, `eu.kanade.tachiyomi.extension.en.mangapill`, `eu.kanade.tachiyomi.animeextension.en.kickassanime` and `eu.kanade.tachiyomi.extension.all.mangadex`. **`extensions(condition: {pkgName})` returns more than that package**: filter the nodes by `pkgName` yourself before checking `isInstalled`;
  5. set $env:MADOYOMI_LIVE_URL=$script:base and $env:MADOYOMI_LIVE_TOKEN=$script:token, and run `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts --silent=false 2>&1"` from app/;
  6. Stop-MadoyomiServer (in a `finally`).

  One server per data dir: H2 locks it. **The exit run's UI check needs a different, empty data dir**: everything there is done through the UI.
- The exit criterion's "install and trust": adding a repo trusts its signing key, so an install from it is trusted with no prompt. Stage 3 reached the trust prompt from the UI by removing yuzono (its confirmation), which leaves AnimeGG "Untrusted" with Trust…, and then re-adding yuzono. Do the same to show the prompt in the exit run, or record how trust was shown.
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, with MADOYOMI_DATA_DIR exported, output to a scratchpad log). Wait on <dataDir>/logs/sidecar.log for a "healthy after" line **from this session** (a fresh data dir has no log yet; otherwise count the lines first), then give the window a few seconds before the first click.
  - Several unrelated java processes are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow().
  - Explorer windows opened by Open logs can be listed and closed through `(New-Object -ComObject Shell.Application).Windows()` (`LocationURL`, `Quit()`).
  - F5 reloads the window (only for diagnosis: the checks must pass without a reload).
- Screenshots, clicks and typing all WORK. Recreate a scratchpad win.ps1 that:
  - calls SetProcessDPIAware, finds the madoyomi process's MainWindowHandle, ShowWindow(9), SetForegroundWindow, then GetWindowRect;
  - `-Action shot -Out x.png` does a System.Drawing CopyFromScreen of that rect;
  - `-Action click -X -Y` does SetCursorPos(rect.L+X, rect.T+Y) + mouse_event(2) / mouse_event(4);
  - `-Action wheel -X -Y -Delta -120 -Count N` does mouse_event(0x0800) N times (-120 is one notch; -2400 scrolls a long list fast);
  - `-Action paste -Text <s>` does Set-Clipboard, then SendKeys `^a`, `^v`;
  - `-Action keys -Text '{ENTER}'` uses SendKeys (`^a{DEL}` clears a field). A native <select> opens on a click and takes `{DOWN}` then `{ENTER}`.

  Coordinates are window-relative physical pixels, read off the screenshot. The window is 1296x839 with its frame. Positions:
  - the sidebar's switch: Anime x≈77 / Manga x≈178, y≈101;
  - Library y≈153, Browse 191, Extensions 229, Settings y≈714;
  - the page's actions buttons at y≈72 (the rightmost at about x≈1210; a source page has Settings x≈1117 and All sources x≈1210; a source's settings page has Browse this source x≈1083);
  - Browse's first source row at y≈190 (pinned or the first language group). MangaDex adds one source per language, so EN is far down: scroll;
  - on a source page: the tabs at y≈152 (Popular x≈316, Latest 387, Search 454), the search box x≈700, Filters x≈962; the first card at about (356, 300);
  - on a details page: Add to library x≈520, y≈176; Refresh x≈619 (x≈660 when the entry is in the library: the buttons move);
  - on Settings: the theme at y≈173 (System x≈960, Dark 1033, Light 1105), Open logs at about (1099, 500), the first source row at y≈618;
  - on a source's settings page: the rows are about 64 px apart from y≈202, with the controls at x≈1138 (selects) or x≈1218 (switches).

  Text that wraps moves everything below it: take a screenshot before clicking inside a sheet or dialog. Reka tabs react to the mousedown of a click. Don't commit screenshots.
- Findings that matter here:
  - Stage 5: AnimeGG and MangaPill give no upload dates; undated rows get the fetch date. KickAssAnime is the anime source whose filters work; AnimeGG's own Genre filter 404s on its site.
  - Stage 6:
    - MangaDex's language sources often have an empty Popular;
    - KickAssAnime's "Use English titles" and MangaDex's "Filter original languages" are visible preferences. Restore whatever you change.
- Reka in jsdom:
  - Tabs change on `mousedown`. Select opens with Enter on its trigger, and an option is picked with a bubbling Enter keydown, once `scrollIntoView`, `hasPointerCapture` and `releasePointerCapture` are stubbed (see FilterRenderer.spec.ts).
  - Collapsible opens on click. Switch, Checkbox and ToggleGroup items react to `click`.
  - Dialogs portal to <body> (mountView attaches there).
  - The virtualizers run in jsdom once clientHeight/clientWidth, offsetHeight and, for VirtualGrid's end check, scrollHeight are stubbed.
- The live spec runs in Node with its own `createApiClient`: the typed calls in operations/ use the app's Pinia-backed `api`, so call the documents through the spec's `client` there. graphql-js refuses upstream's schema (two input types without fields), so the live spec builds it with assumeValid: true. Keep that.
- TypeScript 6 loads no @types by default: in app code there's no `process` and no Array.prototype.at (lib ES2020). `h()` doesn't take a generic SFC: mount it through `as unknown as Component`. ESLint warns about a second component defined in a spec file, and flags unused destructured variables (build a copy and `delete` the key instead). ESLint's `vue/no-side-effects-in-computed-properties` reads any method called `sort` as Array.prototype.sort.
- The route smoke test (src/__tests__/routes.spec.ts) needs an `h1` in `main` on every route without a backend: keep a title while loading.
- PowerShell:
  - variables are case-insensitive (`$pid` is read-only: use `$procId`);
  - a function's pipeline output is its return value;
  - PS 5.1 has no `??`;
  - with $ErrorActionPreference='Stop', any native stderr line aborts the script;
  - keep .ps1 files ASCII;
  - write files with [IO.File]::WriteAllText + UTF8Encoding($false) or the Write tool.
- Shell edits: backslashes inside perl -e / sed replacements get mangled, and Bash heredocs with many quotes can fail to parse. A perl script file fed with `perl script.pl < in > out` works for multi-line replacements; the Write/Edit tools are safer for code. `pnpm format` rewrites files: read them again before an Edit.
- A vitest `--reporter=json` run without a valid --outputFile writes app/.vitest/: delete it, never commit it.
- The token must stay out of logs and URLs, except where the contract needs ?token= (img src, WebSocket). Never print it in test output or docs. Check sidecar.log, madoyomi.log and the dev output for `token=` at the end.
- Adding npm packages needs network access; pnpm-lock.yaml changes are expected and are text.
- Python and gh are not installed.

Tasks (Stage 7):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-exit`. Never edit on main. (If feat/phase-3-settings isn't merged yet, stop and tell me.)
1. Baseline: rerun everything in "Baseline" (smoke included) and record it.
2. Step 22, tests: check that each area step 22 names is covered: FilterRenderer and PreferenceRenderer unit tests, API client error handling, the route smoke test. Fill real gaps found in the §10 "Not checked live" lists that a test can cover; don't add tests for their own sake. Tick "Component + client tests" when that holds.
3. Step 23 and the §6 exit run, from a **fresh, empty** MADOYOMI_DATA_DIR in `pnpm tauri dev`, all in the UI by clicks and screenshots:
   - add both repos (Repos, type detected);
   - install one anime and one manga extension (e.g. KickAssAnime and MangaPill) with their steps shown, and show the trust prompt (see the gotcha);
   - browse and search each with filters (a filtered search that changes the results);
   - open details, see the episodes/chapters, and add one of each to the library; the Library shows them;
   - change a source preference of each kind and see it take effect (recheck), then restore it;
   - kill the backend: the overlay, the recovery on a new port, and the screens still working without a reload (recheck);
   - the failure screen's Retry and Open logs (Stage 1's open item), e.g. with MADOYOMI_SIDECAR_JAR pointing at a missing jar;
   - at the end, no `token=` in the logs.
4. Fix round: fix what the exit run finds (UI or, if needed, server: then rebuild, add tests, UPSTREAM.md), and recheck each fix live.
5. Run `pnpm lint`, `pnpm typecheck`, `pnpm format:check`, `pnpm test`, the live spec against a seeded server, and `cargo build`/`cargo test`/`cargo clippy --all-targets`. Tick §5 "`pnpm lint`, `pnpm typecheck`, `pnpm test` pass" when they do.
6. Phase doc:
   - §5 and §6 ticks (only verified items);
   - §8 stage 7 *Done*, with a phase-done line;
   - §9 decisions and §10 findings of the exit run, including what wasn't checked and why.
7. docs/PLAN.md:
   - tick "lint / typecheck / tests pass" when verified. Mark Phase 3 done (with the date and a summary like Phase 2's) only if every §6 exit criterion passes;
   - update §8 "First tasks" (Phase 4 next) and §6 risks / §7 open decisions if anything changed. The mpv window question stays open: Phase 4 decides it.
8. README.md status:
   - the warning box, the Roadmap row for Phase 3 (✅ done, if so), the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list (rename it "Decided in Phase 3" if the phase is done);
   - make it match the phase doc and PLAN.md;
   - also update app/README.md if anything in it changed.
9. At the end, write the ready-to-paste prompt for the next stage (Phase 4 Stage 1: read docs/phases/phase-4-playback.md and propose its stage plan first) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 220 tests (the live spec skipped);
  - the live spec passes 9/9 (or more) against a seeded server;
  - `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- The exit run: every §6 criterion checked from a fresh data dir in `pnpm tauri dev`, with screenshots (not committed) and the findings in §10.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 667 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-exit, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files;
  - hs_err logs, heap dumps, screenshots;
  - node_modules, target/, dist/, app/.vitest/, .env.local;
  - tools/compat-test/out, tools/dev-data.

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
