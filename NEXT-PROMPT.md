Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 4: Browse and Source, with FilterRenderer (steps 13-14, 18, 20-21; the "Browse (source list)", "Source (popular/latest/search + filters)" and "`FilterRenderer` (all filter types + reset)" checklist items, and the source-grid part of "Virtualized grids/lists" and "Covers via backend image proxy"). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 3 doc, all of it: §4 steps, §5 checklist, §6 exit criteria, §7 risks ("filter state must round-trip exactly"), §8 stage plan, §9 decisions (Stages 1-3), §10 findings (Stages 1-3);
- server/UPSTREAM.md:
  - "Startup contract";
  - "API (Phase 1 Stage 7)";
  - "Anime sources (Phase 2)", for the anime source, filter, browse and thumbnail operations and the error codes;
- the Phase 2 doc's §4 step 16 (the anime API mapping) and its §9 on filters ("Filters and preferences use upstream's GraphQL types");
- README.md (the status box, the Roadmap, the decisions, and the NOTE on pnpm tauri dev);
- app/README.md (the structure, the routes, the live operation check).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stages 1-3 done; Stage 3 on branch feat/phase-3-extensions):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar.
  - Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md.
  - Stages 1-3 changed nothing in server/.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST only for binaries:
    - thumbnails: `/api/v1/anime/{id}/thumbnail` and upstream's `/api/v1/manga/{id}/thumbnail`, which already apply the source's headers, so step 21 needs no new proxy;
    - pages;
    - extension icons.
    GETs may pass ?token=.
  - Errors carry {code, details}. SOURCE_FAILED means a source's site failed or was unreachable; UNTRUSTED and NOT_FOUND are diagnosed by SourceAccess.
  - Browse is a mutation per kind: BROWSE_ANIME_MUTATION / BROWSE_MANGA_MUTATION (fetchSourceAnime/fetchSourceManga with type POPULAR/LATEST/SEARCH, page, query, filters).
  - Filters come from ANIME_SOURCE_FILTERS_QUERY / MANGA_SOURCE_FILTERS_QUERY, as upstream's `Filter` union:
    - Header, Separator, Select, Text, CheckBox, TriState, Sort, and Group (one level, never nested).
    - The union's same-named fields are aliased by kind: selectDefault, textDefault, checkBoxDefault, triStateDefault, sortDefault.
  - The input is `FilterChange {position, selectState|textState|checkBoxState|triState|sortState|groupChange}`, where position is in the list or the group; see api/types.ts.
- The shell (app/src-tauri/src/sidecar.rs, Stage 1):
  - It runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it), and restarts it after a crash.
  - The port changes after every restart.
- The UI (app/README.md has the structure):
  - src/api/:
    - client.ts: `api.gql`/`gqlResult`, `ApiError {code, message, details, path, status, kind}`, `api.assetUrl(path)` (adds ?token= to server-relative paths only), and `useAssetUrl(path)` (a computed that follows restarts; use it for covers);
    - errorText.ts: `describeError(e)` gives a readable sentence per code, and ErrorNotice shows it (its "Try again" only with @retry);
    - types.ts: hand-written. operations/: documents + typed calls; exports must end in _QUERY/_MUTATION/_SUBSCRIPTION, so the live spec validates them.
  - src/composables/:
    - useSources(kind), useSourceFilters(kind, sourceId) (staleTime Infinity), and useSourceBrowse(params), an infinite query of pages while hasNextPage, keyed by kind/source/type/query/filters;
    - useExtensions.ts (Stage 3: repo and extension mutations, useExtensionActionState);
    - queryKeys.ts.
  - Queries retry only NETWORK/BACKEND_UNAVAILABLE; mutations never retry.
  - Components:
    - PageLayout (title, subtitle, actions slot; `fill` gives the last child the remaining height, for a page with its own scroll area);
    - ErrorNotice, PagePlaceholder;
    - VirtualList (generic, @tanstack/vue-virtual with measured rows, its own scroll area, every row rendered below `threshold` = 60, `resetKey` resets the scroll);
    - ExtensionRow, TrustDialog.
    Build a VirtualGrid for the source results on the same pattern: rows of cards sized from the container width.
  - Shared styles in styles/main.css:
    - .btn, .btn-accent, .btn-small, .field (text field or native select), .badge (.warn/.danger/.ok);
    - .dialog*, for Reka dialogs, and .flyout, for popovers;
    - `a` in the accent colour.
    Tokens are in styles/tokens.css. Components use tokens only, never raw colours.
  - Routes: /browse (BrowseView already lists the current kind's sources by language: replace it with the real screen) and /browse/:sourceId (SourceView is a placeholder; the route uses the current kind).
  - Test helpers in src/test/: mountView (Pinia, vue-query and a memory router, attached to <body> for Reka's portals), setBackendReady, fixtures (makeStore, makeExtension, installed), withSetup, fakeSocket.
- Decided (Phase 3 §9):
  - Reka UI primitives with our own styles: use Reka Select in FilterRenderer's Select and Sort, Checkbox with its indeterminate state for TriState, Collapsible for Group, and Dialog or a side sheet for the filter sheet.
  - @tanstack/vue-virtual for long lists; dark + light themes; a Fluent-like look.
  - Native <select> is fine for a plain list of strings (Stage 3).

Baseline:
- server: `.\gradlew.bat build` is green with 662 tests, 0 failures: core 604 (8 skipped), AndroidCompat 7, ext-host 8, api 43. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Rebuild only if server/ changes.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass (rerun at the start of Stage 3).
- app: `pnpm test` gives 14 files, 107 passed + 6 skipped.
  - That's client 15, ws 13, realtime 6, hooks 9, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6, TrustDialog 5, VirtualList 2, ExtensionsView 11, ReposView 8.
  - The 6 skipped are the live spec.
  - `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 6/6.
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 6 min).
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat.
  - `.\gradlew.bat :api:shadowJar` builds only the jar.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio. It sets $script:base and $script:token, and GqlData runs a query.
- Seeding a data dir (the Stage 2/3 recipe): write a scratchpad .ps1 that does these steps, run it as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> *> <log>`, and then start `pnpm tauri dev` with MADOYOMI_DATA_DIR=<that dir>:
  1. dot-source tools/lib/MadoyomiServer.ps1;
  2. `Start-MadoyomiServer <java> (Resolve-MadoyomiJar <repo> '' -NoBuild) <scratch data dir>`;
  3. add the repos with GqlData (`addExtensionStore(input: {indexUrl, type})`), and install AnimeGG and MangaPill (InstallWithProgress in MadoyomiServer.ps1, or `updateExtension(input: {id, patch: {install: true}})`);
  4. optionally set $env:MADOYOMI_LIVE_URL=$script:base and $env:MADOYOMI_LIVE_TOKEN=$script:token, and run `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts 2>&1"` from app/;
  5. Stop-MadoyomiServer.
  One server per data dir: H2 locks it. Stage 3 showed that the whole flow can also be driven in the UI (see typing below).
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, output to a scratchpad log), then wait on <dataDir>/logs/sidecar.log for a "healthy after" line from this session (the old session's lines stay until the log rotates).
  - Several unrelated java processes are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi).CloseMainWindow().
- Screenshots, clicks and typing all WORK. Recreate a scratchpad win.ps1 that:
  - calls SetProcessDPIAware, finds the madoyomi process's MainWindowHandle, ShowWindow(9), SetForegroundWindow, then GetWindowRect;
  - `-Action shot -Out x.png` takes System.Drawing CopyFromScreen of that rect;
  - `-Action click -X -Y` does SetCursorPos(rect.L+X, rect.T+Y) + mouse_event(2) / mouse_event(4);
  - `-Action wheel -X -Y -Delta -1200` does mouse_event(0x0800) to scroll;
  - `-Action paste -Text <s>` does Set-Clipboard, then System.Windows.Forms SendKeys `^a`, `^v`;
  - `-Action keys -Text '{DOWN}{ENTER}'` uses SendKeys, which drives native selects and clears a field with `^a{BACKSPACE}`.
  Coordinates are window-relative physical pixels, read off the screenshot. The window is 1296x839 with its frame. The sidebar's switch is at Anime x≈77 / Manga x≈178, y≈101; Library y≈153, Browse 191, Extensions 229; the page's actions button at about x≈1210, y≈72. Reka tabs react to the mousedown of a click. Don't commit screenshots.
- Stage 3 findings to handle here:
  - Upstream's "Local source" (manga, source id 0) still shows as "Untrusted" in Browse: its fake extension has no signature. Treat id 0 as built in (no Untrusted badge).
  - An anime source that can't be used (untrusted, not loadable) has supportsLatest/isConfigurable null: offer no Latest tab, and say why.
  - Browse a source only when its extension is trusted and loaded; otherwise point to Extensions (the trust prompt).
- Reka in jsdom:
  - Tabs change on `mousedown` (a pointer press), not on `click`.
  - Dialogs and popovers portal to <body>, so mount with attachTo: document.body (mountView does).
  - The virtualizer runs in jsdom once clientHeight (the viewport) and offsetHeight (the rows) are stubbed (see VirtualList.spec.ts).
- graphql-js refuses upstream's schema (two input types without fields), so the live spec builds it with assumeValid: true. Keep that.
- TypeScript 6 loads no @types by default: in app code there's no `process` (see how operations.live.spec.ts reads env) and no Array.prototype.at (lib ES2020). `h()` doesn't take a generic SFC: mount it through `as unknown as Component`, with props and slots.
- PowerShell:
  - variables are case-insensitive (a local can be a parameter);
  - a function's pipeline output is its return value;
  - PS 5.1 has no `??`;
  - with $ErrorActionPreference='Stop', any native stderr line aborts the script;
  - keep .ps1 files ASCII;
  - write files with [IO.File]::WriteAllText + UTF8Encoding($false) or the Write tool.
- Shell edits: backslashes inside perl -e / sed replacements get mangled, and Bash heredocs with many quotes can fail to parse. Use the Write/Edit tools for such content. `pnpm format` rewrites files: read them again before an Edit.
- A vitest `--reporter=json` run without a valid --outputFile writes app/.vitest/: delete it, never commit it.
- The token must stay out of logs and URLs, except where the contract needs ?token= (img src, WebSocket). Never print it in test output or docs.
- Adding npm packages needs network access; pnpm-lock.yaml changes are expected and are text.
- Python and gh are not installed.

Tasks (Stage 4):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-browse`. Never edit on main. (If feat/phase-3-extensions isn't merged yet, stop and tell me.)
1. API: add or adjust whatever the screens need in src/api/operations/ and types.ts (e.g. the source fields the source page shows), keeping the _QUERY/_MUTATION names so the live spec validates them. Add composables as needed: pinned sources, the filter state.
2. Browse (step 13):
   - the current kind's installed sources grouped by language;
   - pin favourites, persisted (decide where: localStorage or a server-side setting, and record why);
   - the Local source as built in;
   - an untrusted or unloadable source shown as such, with a link to Extensions.
3. Source page (step 14):
   - Popular / Latest / Search tabs (no Latest when supportsLatest is false or null), and a search box;
   - a filter sheet with FilterRenderer, with Apply and Reset;
   - an infinite VirtualGrid of MediaCards (cover via useAssetUrl on the entry's thumbnailUrl, title, in-library mark) that loads the next page near the end;
   - errors (SOURCE_FAILED, UNTRUSTED, NOT_FOUND) shown readably, with a retry;
   - a card links to /anime/:id or /manga/:id (Stage 5 builds those).
4. FilterRenderer (step 18): Header, Separator, Text, CheckBox, TriState (3-state), Select, Sort (field + asc/desc), and Group (collapsible).
   - It emits the `FilterChange[]` the backend understands: only the changed filters, `position` in the list or group, and `groupChange` for group members.
   - It supports reset and round-trips exactly (phase-3 §7).
   - Keep the filter state per source while the app runs.
5. Tests:
   - FilterRenderer unit tests on shared fixtures: every type, the changes it emits, group positions, reset, defaults not emitted;
   - component tests for Browse (grouping, pinning, the Local source) and the Source page (tabs, search, the next page, an error);
   - a VirtualGrid test like VirtualList's;
   - keep the route smoke test passing;
   - extend the live spec if new documents need checking, and consider a live filtered search (needs an installed source and network).
6. Live check in `pnpm tauri dev` (a seeded data dir with both repos and AnimeGG + MangaPill installed is fine), by clicks and screenshots:
   - Browse for both kinds; pin a source;
   - Popular and Search for both kinds;
   - a filtered search for both kinds (e.g. a genre or sort filter), checking the results change;
   - scroll to load page 2;
   - after a backend kill, the source page still works without reloading the window.
7. Phase doc: §5 ticks (only verified items), §9 decisions, §10 findings. Update the §8 stage plan if the plan changes.
8. docs/PLAN.md:
   - Tick the Phase 3 milestones only if every phase-doc item behind them is verified. "Repos, Extensions …, Browse, Source, Details, Library, Settings screens" won't be after this stage (Details, Library and Settings remain), and "Generic filter and preference renderers" won't be either (PreferenceRenderer is Stage 6). Leave both unticked and say which parts are done.
   - Keep Phase 3 "in progress" in §4 and §8 "First tasks", with the stage number.
   - Update §6 risks and §7 open decisions if this stage decides anything (the mpv window question stays open).
9. README.md status:
   - the warning box (what works now), the Roadmap row for Phase 3, the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list;
   - make it match the phase doc and PLAN.md;
   - also update app/README.md (the structure and the routes table).
10. At the end, write the ready-to-paste prompt for the next stage (Stage 5: Details and Library) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 107 tests plus the new ones (the live spec skipped);
  - the live spec passes 6/6 (or more) against a seeded server;
  - `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- `pnpm tauri dev`: Browse and a source page for both kinds; a filtered search that changes the results; page 2 loaded by scrolling; all of it still working after a backend kill, without reloading the window.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 662 tests), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-browse, with only text files. None of these:
  - jars, APKs, .pb files, fonts, test images, DB files;
  - hs_err logs, heap dumps, screenshots;
  - node_modules, target/, dist/, app/.vitest/, .env.local;
  - tools/compat-test/out, tools/dev-data.

Standing rules:
- Start every stage by switching to a new feature branch off an up-to-date main (git switch main, git pull, git switch -c feat/phase-3-<topic>) before any edit. Never edit on main.
- Work one stage at a time.
- Never commit (no git commit/merge/rebase/cherry-pick/revert). Never commit or stage binaries, APKs, jars, .pb files or heap dumps.
- Ask me only about decisions that are genuinely mine.
- Every stage updates, together with the phase doc and, for server changes, server/UPSTREAM.md:
  - README.md's status (the status box, the Roadmap row, the changed tables, the decisions);
  - docs/PLAN.md's milestones (tick what the phase doc verifies; update the phase status, the risks and the open decisions).
- At the end of the stage, stop and report what changed, the decisions and findings, how you verified it (test counts against the baseline), a one-line commit message and a PR title.
- Then write a ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it at the end of your report. Use this same format: current state, baseline test counts, gotchas, the tasks with the branch switch as task 0 and the README status / PLAN.md tick tasks, verification steps including the README and PLAN.md checks, and these standing rules. Tell that agent to do the same.
- Then wait for me to commit or merge.
