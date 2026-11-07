Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 6: Settings and PreferenceRenderer (steps 17 and 19; the "Settings + source preferences" and "`PreferenceRenderer` (all preference types)" checklist items, and the exit criterion "Source preferences can be changed in the UI and take effect"). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 3 doc, all of it: §4 steps (17, 19), §5 checklist, §6 exit criteria, §8 stage plan, §9 decisions (Stages 1-5), §10 findings (Stages 1-5);
- server/UPSTREAM.md:
  - "Startup contract";
  - "API (Phase 1 Stage 7)";
  - "Anime sources (Phase 2)", for `AnimeSourcePreferences`, `updateAnimeSourcePreference` and `isConfigurable`;
  - "Library flag (Phase 3)";
- the Phase 2 doc's §4 step 16 (the anime API mapping) and its §9 "Stage 4" (preferences in upstream's shapes);
- README.md (the status box, the Roadmap, the decisions, and the NOTE on pnpm tauri dev);
- app/README.md (the structure, the routes, the live operation check).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stages 1-5 done; Stage 5 on branch feat/phase-3-details):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar.
  - Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md.
  - Stage 5 added `updateAnime`/`updateAnimes` (Madoyomi code in `madoyomi.api.graphql.AnimeMutation`, no upstream edit) and `AnimeLibraryApiTest`.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST only for binaries (thumbnails, pages, extension icons). GETs may pass ?token=.
  - Errors carry {code, details}. SOURCE_FAILED means a source's site failed or was unreachable; UNTRUSTED and NOT_FOUND are diagnosed by SourceAccess.
  - Preferences, both in upstream's `Preference` union shape (`SwitchPreference`, `CheckBoxPreference`, `EditTextPreference`, `ListPreference`, `MultiSelectListPreference`):
    - anime: `animeSources(condition: {id})` → `preferences`, and `updateAnimeSourcePreference(input: {source, change: {position, switchState | checkBoxState | editTextState | listState | multiSelectState}})` → `{preferences, source}` (permission `MANAGE_SOURCE_PREFERENCES`; the extension's change listener runs first and may refuse; the cached source is dropped so the change takes effect);
    - manga: upstream's `source(id) { preferences }` and `updateSourcePreference(input: {source, change})` → `{preferences, source}`;
    - `isConfigurable` on a source says whether it has preferences (null for an anime source that can't be used).
  - **No preference document exists yet** in app/src/api/operations/: only the types (`Preference` union with aliased fields such as `listCurrentValue`, `multiSelectCurrentValue`; `SourcePreferenceChange`) are in types.ts. Aliasing is required, because the union's members have fields of the same name and different types (Phase 3 §9 "Stage 2").
- The shell (app/src-tauri/src/sidecar.rs, Stage 1): runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it), and restarts it after a crash. The port changes after every restart. The status snapshot (`get_backend_status`, stores/backend.ts) already has `dataDir` and `logDir`; `open_backend_logs` shows sidecar.log in Explorer.
- The UI (app/README.md has the structure):
  - src/api/: client.ts (`api.gql`/`gqlResult`, `ApiError`, `useAssetUrl`), errorText.ts + ErrorNotice (its "Try again" only with @retry), types.ts (hand-written), operations/ (documents + typed calls; exports must end in _QUERY/_MUTATION/_SUBSCRIPTION, so the live spec validates them).
  - src/composables/: useSources/useSourceFilters/useSourceBrowse, useEntries.ts (entries, refreshes, library), useDetailsPage, useExtensions.ts, useOpenInExtensions, queryKeys.ts. Queries retry only NETWORK/BACKEND_UNAVAILABLE; mutations never retry. Mutations write what the server returns into the cache (see useSetInLibrary).
  - src/stores/: backend, events, mediaKind, extensionFilters, pinnedSources, sourceBrowse, itemList, librarySort (the persisted ones use `localStorage` with a `madoyomi.*` key and a sync watch, see stores/librarySort.ts).
  - src/utils/: filters.ts, sources.ts (`sourceAccess`, `canBrowse`), entryItems.ts, format.ts.
  - Components: PageLayout (`fill`; the title is optional), ErrorNotice, PagePlaceholder, VirtualList, VirtualGrid, MediaCard, EntryHeader, EntryItemList/EpisodeList/ChapterList, FilterRenderer/FilterField/FilterSheet (Stage 4: the pattern for a generic renderer driven by the backend's union, with Reka Select, Checkbox, Switch), ExtensionRow, TrustDialog.
  - Shared styles in styles/main.css (.btn, .btn-accent, .btn-small, .field, .badge, .dialog*, .flyout). Tokens in styles/tokens.css: the theme follows `prefers-color-scheme` unless `<html data-theme="dark|light">` overrides it, which is what Settings' System/Dark/Light must set (Phase 3 §9 "Stage 1").
  - Routes: /settings (SettingsView placeholder) and /settings/source/:id (SourceSettingsView placeholder; it uses the current kind). Nothing links to a source's settings yet: Browse and the source page could link there when `isConfigurable` is true.
  - Test helpers in src/test/: mountView, setBackendReady, fixtures (makeStore, makeExtension, installed, makeSource, makeMangaSource, makePage, makeAnime, makeManga, makeEpisodes, makeChapters), filterFixtures, withSetup, fakeSocket.
- Decided (Phase 3 §9): Reka UI primitives with our own styles; @tanstack/vue-virtual for long lists; dark + light themes; a Fluent-like look; native <select> is fine for a plain list of strings. Stage 5: the library flag on the server; manga refresh in two calls; an automatic refresh only on the first open of a never-fetched entry; the list's sort per kind (persisted), its filters per entry (session).
- Step 19 asks for optimistic updates when a preference is saved. Decide how a refused change (the extension's listener) or a failed save rolls back, and record it.

Baseline:
- server: `.\gradlew.bat build` is green with 667 tests, 0 failures: core 604 (9 skipped), AndroidCompat 7, ext-host 8, api 48. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Stage 6 likely changes nothing in server/ (the preference API exists); if it does, rebuild and add tests.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass (last run at the end of Stage 5 on the new jar; rerun at the start of this stage).
- app: `pnpm test` gives 25 files, 186 passed + 8 skipped.
  - That's client 15, ws 13, realtime 6, entries 7, hooks 10, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6, TrustDialog 5, VirtualList 2, VirtualGrid 5, EntryItemList 5, FilterRenderer 8, filters 10, entryItems 5, ExtensionsView 11, ReposView 8, BrowseView 6, SourceView 12, AnimeDetailsView 9, MangaDetailsView 6, LibraryView 5.
  - The 8 skipped are the live spec.
  - `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 8/8 (the last two are a filtered search per kind and a refresh + library round trip per kind). 27 documents validate.
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 4-6 min).
  - From the Bash tool: `export JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21' && cmd //c ".\\gradlew.bat build --console=plain"` from server/.
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat. `.\gradlew.bat :api:shadowJar` builds only the jar; `build` rebuilds the jar too. `pnpm tauri dev` and the seed script use the newest jar.
  - `.\gradlew.bat :api:ktlintCheck :api:test --tests <class>` is a quick check of one api test class.
  - New server code goes under `madoyomi.*` in `api`; an upstream file edit needs a `// Madoyomi:` comment and an UPSTREAM.md row.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio. It sets $script:base and $script:token, and GqlData runs a query.
- Seeding a data dir (the Stage 5 recipe): a scratchpad seed.ps1, run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> > <log> 2>&1`:
  1. dot-source tools/lib/MadoyomiServer.ps1;
  2. `Start-MadoyomiServer <java> (Resolve-MadoyomiJar <repo> '' -NoBuild) <scratch data dir>`;
  3. add both repos with GqlData (`addExtensionStore(input: {indexUrl, type})`, if `extensionStores` has none of that type): yuzono `https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json` (ANIME), Keiyoushi `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb` (MANGA);
  4. install AnimeGG, MangaPill and KickAssAnime (`eu.kanade.tachiyomi.animeextension.en.animegg`, `eu.kanade.tachiyomi.extension.en.mangapill`, `eu.kanade.tachiyomi.animeextension.en.kickassanime`) with InstallWithProgress. **`extensions(condition: {pkgName})` returns more than that package**: filter the nodes by `pkgName` yourself before checking `isInstalled`. For preferences you need a configurable source: check `isConfigurable` on these first; AnimePahe (`eu.kanade.tachiyomi.animeextension.en.animepahe`) has preferences (Phase 2 tests use it), though its site is behind Cloudflare, which doesn't matter for its preferences;
  5. set $env:MADOYOMI_LIVE_URL=$script:base and $env:MADOYOMI_LIVE_TOKEN=$script:token, and run `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts --silent=false 2>&1"` from app/;
  6. Stop-MadoyomiServer (in a `finally`).
  One server per data dir: H2 locks it.
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, with MADOYOMI_DATA_DIR exported, output to a scratchpad log). Wait on <dataDir>/logs/sidecar.log for a "healthy after" line **from this session** (a fresh data dir has no log yet; otherwise count the lines first), then give the window a few seconds before the first click.
  - Several unrelated java processes are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow().
- Screenshots, clicks and typing all WORK. Recreate a scratchpad win.ps1 that:
  - calls SetProcessDPIAware, finds the madoyomi process's MainWindowHandle, ShowWindow(9), SetForegroundWindow, then GetWindowRect;
  - `-Action shot -Out x.png` does a System.Drawing CopyFromScreen of that rect;
  - `-Action click -X -Y` does SetCursorPos(rect.L+X, rect.T+Y) + mouse_event(2) / mouse_event(4);
  - `-Action wheel -X -Y -Delta -2400 -Count N` does mouse_event(0x0800) N times;
  - `-Action paste -Text <s>` does Set-Clipboard, then SendKeys `^a`, `^v`;
  - `-Action keys -Text '{ENTER}'` uses SendKeys. A native <select> opens on a click and takes `{DOWN}` then `{ENTER}`.

  Coordinates are window-relative physical pixels, read off the screenshot. The window is 1296x839 with its frame. Positions:
  - the sidebar's switch: Anime x≈77 / Manga x≈178, y≈101;
  - Library y≈153, Browse 191, Extensions 229, Settings y≈714;
  - the page's actions button at about x≈1210, y≈72;
  - Browse's first source row at y≈190 (pinned or the first language group);
  - on a source page: the tabs at y≈152 (Popular x≈316, Latest 387, Search 454), the search box x≈700, Filters x≈962; the first card at about (356, 300);
  - on a details page: Add to library x≈520, y≈176; Refresh x≈619 (x≈660 when the entry is in the library: the buttons move).

  Text that wraps moves everything below it: take a screenshot before clicking inside a sheet. Reka tabs react to the mousedown of a click. Don't commit screenshots.
- Stage 5 findings that matter here:
  - AnimeGG and MangaPill give no upload dates; undated rows get the fetch date.
  - KickAssAnime is the anime source whose filters work; AnimeGG's own Genre filter 404s on its site.
  - Seeded entries opened in Stage 5 are initialized; a fresh data dir gives fresh ones.
- Reka in jsdom:
  - Tabs change on `mousedown`. Select opens with Enter on its trigger, and an option is picked with a bubbling Enter keydown, once `scrollIntoView`, `hasPointerCapture` and `releasePointerCapture` are stubbed (see FilterRenderer.spec.ts).
  - Collapsible opens on click. Switch and Checkbox react to `click`.
  - Dialogs portal to <body> (mountView attaches there).
  - `CollapsibleContent` keeps its element with `hidden=""`: a `display` rule on it needs a `[hidden] { display: none }` partner.
  - The virtualizers run in jsdom once clientHeight/clientWidth (the viewport), offsetHeight (the rows) and, for VirtualGrid's end check, scrollHeight are stubbed (VirtualList.spec.ts, EntryItemList.spec.ts, VirtualGrid.spec.ts).
- The live spec runs in Node with its own `createApiClient`: the typed calls in operations/ use the app's Pinia-backed `api`, so call the documents through the spec's `client` there.
- graphql-js refuses upstream's schema (two input types without fields), so the live spec builds it with assumeValid: true. Keep that.
- TypeScript 6 loads no @types by default: in app code there's no `process` (see how operations.live.spec.ts reads env) and no Array.prototype.at (lib ES2020). `h()` doesn't take a generic SFC: mount it through `as unknown as Component`, with props and slots. ESLint warns about a second component defined in a spec file. ESLint's `vue/no-side-effects-in-computed-properties` reads any method called `sort` as Array.prototype.sort: don't name a store method `sort`.
- The route smoke test (src/__tests__/routes.spec.ts) needs an `h1` in `main` on every route without a backend ("Settings", "Source settings"): keep a title while loading.
- PowerShell:
  - variables are case-insensitive (a local can be a parameter, e.g. `$pid` is read-only: use `$procId`);
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

Tasks (Stage 6):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-settings`. Never edit on main. (If feat/phase-3-details isn't merged yet, stop and tell me.)
1. API: the preference operations in src/api/operations/ (keep the _QUERY/_MUTATION names and the aliased union fields):
   - the preferences of a source of each kind (`animeSources(condition: {id}) { preferences }`, upstream's `source(id) { preferences }`);
   - `updateAnimeSourcePreference` and `updateSourcePreference` with a `SourcePreferenceChange`;
   - composables: a preferences query per kind and source, and a mutation with optimistic updates (step 19) that rolls back on an error or a refusal and then writes the server's `preferences` into the cache.
2. `PreferenceRenderer` (step 19): List (Reka Select), EditText (a field with save on Enter/blur or a small dialog, with the dialog title/message), Switch and CheckBox (Reka Switch/Checkbox), MultiSelect (checkboxes in a dialog or popover), each with its title and summary, disabled states (`enabled`/visible) if the type has them. Only the changed preference is sent, by its position. Readable errors.
3. Settings (step 17), /settings:
   - General: theme System/Dark/Light (sets or removes `<html data-theme>`, persisted in localStorage, applied before the first paint if cheap), and language (English only for now: say so);
   - Backend: the data dir and the log folder from the backend status, an "Open logs" button (`open_backend_logs`), the server version and URL;
   - Source preferences: the current kind's configurable sources (`isConfigurable`), each linking to /settings/source/:id;
   - /settings/source/:id: the source's name and kind, its preferences through PreferenceRenderer, an unusable source handled like the source page (Open in Extensions);
   - consider a "Settings" link on the source page (and Browse rows) when `isConfigurable` is true.
4. Tests:
   - PreferenceRenderer unit tests for every preference type, the change sent for each, and the optimistic update with its rollback;
   - SettingsView (theme switch sets `data-theme` and persists, the backend info, the source list) and SourceSettingsView tests;
   - keep the route smoke test passing;
   - extend the live spec: the new documents validate on their own; add a live preference round trip on a configurable source of a kind (change one preference, read it back, restore it).
5. Live check in `pnpm tauri dev` (a seeded data dir with a configurable source), by clicks and screenshots:
   - switch the theme between System, Dark and Light;
   - open the backend logs folder;
   - change a source preference of each kind where one is configurable, and see that it takes effect (e.g. a different result order, quality or domain on the source page), then restore it;
   - after a backend kill, settings and preferences still work without reloading the window.
6. Phase doc: §5 ticks (only verified items: "Settings + source preferences", "`PreferenceRenderer` (all preference types)"), the §6 exit criterion "Source preferences can be changed in the UI and take effect" if verified, §9 decisions, §10 findings. Update the §8 stage plan if the plan changes.
7. docs/PLAN.md:
   - Tick "Repos, Extensions (with trust prompt), Browse, Source, Details, Library, Settings screens" and "Generic filter and preference renderers" only if every phase-doc item behind them is verified. Leave "lint / typecheck / tests pass" for the exit run (Stage 7).
   - Keep Phase 3 "in progress" in §4 and §8 "First tasks", with the stage number.
   - Update §6 risks and §7 open decisions if this stage decides anything (the mpv window question stays open).
8. README.md status:
   - the warning box (what works now), the Roadmap row for Phase 3, the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list;
   - make it match the phase doc and PLAN.md;
   - also update app/README.md (the structure and the routes table).
9. At the end, write the ready-to-paste prompt for the next stage (Stage 7: the exit run, steps 22-23 and §6, from a fresh data dir) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 186 tests plus the new ones (the live spec skipped);
  - the live spec passes 8/8 (or more) against a seeded server;
  - `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- `pnpm tauri dev`: the theme switch, the backend info and logs, a source preference changed and taking effect for each kind where possible; all of it still working after a backend kill, without reloading the window.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 667 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-settings, with only text files. None of these:
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
