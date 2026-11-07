Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 5: Details and Library (steps 15-16, 20-21; the "Details (anime + manga) with episode/chapter list" and "Library" checklist items, and what remains of "Virtualized grids/lists" (the library grid, episode/chapter lists with 1000+ entries) and "Covers via backend image proxy" (the details and library covers)). Follow CLAUDE.md first. Read these before anything else:
- docs/PLAN.md;
- the Phase 3 doc, all of it: §4 steps, §5 checklist, §6 exit criteria, §7 risks ("virtualize from the start"), §8 stage plan, §9 decisions (Stages 1-4), §10 findings (Stages 1-4);
- server/UPSTREAM.md:
  - "Startup contract";
  - "API (Phase 1 Stage 7)";
  - "Anime sources (Phase 2)", for `anime`/`animes`/`episodes`, `fetchAnimeAndEpisodes`, the Seasons entries and the thumbnail route;
- the Phase 2 doc's §4 step 16 (the anime API mapping) and its §9 "Stage 4" (the shapes of upstream's manga operations);
- README.md (the status box, the Roadmap, the decisions, and the NOTE on pnpm tauri dev);
- app/README.md (the structure, the routes, the live operation check).
Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stages 1-4 done; Stage 4 on branch feat/phase-3-browse):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar.
  - Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md.
  - Stages 1-4 changed nothing in server/.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>, with no subprotocol.
  - REST only for binaries: thumbnails (`/api/v1/anime/{id}/thumbnail`, upstream's `/api/v1/manga/{id}/thumbnail`, both applying the source's headers; `thumbnailUrl` on an entry points there), pages, extension icons. GETs may pass ?token=.
  - Errors carry {code, details}. SOURCE_FAILED means a source's site failed or was unreachable; UNTRUSTED and NOT_FOUND are diagnosed by SourceAccess.
  - Entries:
    - anime: `anime(id)`, `animes(condition, filter, order, paging)` (condition has `inLibrary`), `episodes(condition: {animeId}, …)`, and `fetchAnimeAndEpisodes(input: {id, fetchAnime, fetchEpisodes})` → `{anime, episodes, seasons}` with the source's error, if any, in `errors[]` (use `api.gqlResult` for the partial result). A `fetchType: Seasons` entry has seasons (other entries with `parentId`) instead of episodes;
    - manga: upstream's `manga(id)`, `mangas(…)`, `chapters(…)`, `fetchManga`, `fetchChapters`, and `updateManga(input: {id, patch: {inLibrary}})`.
  - **There is no anime counterpart of `updateManga`.** Adding an anime to the library needs a small server change: an `updateAnime(input: {id, patch: {inLibrary}})` (and probably `updateAnimes`) in `api` (`madoyomi.api.graphql.AnimeMutation`), in upstream's shape, setting `in_library` and `added_at` like upstream's `updateManga`. Check first that nothing else already does it.
- The shell (app/src-tauri/src/sidecar.rs, Stage 1): runs the newest server jar with a per-session token and --data-dir %APPDATA%\Madoyomi (MADOYOMI_DATA_DIR overrides it), and restarts it after a crash. The port changes after every restart.
- The UI (app/README.md has the structure):
  - src/api/:
    - client.ts: `api.gql`/`gqlResult`, `ApiError`, `api.assetUrl(path)` and `useAssetUrl(path)` (a computed that follows restarts; use it for covers);
    - errorText.ts + ErrorNotice (its "Try again" only with @retry);
    - types.ts: hand-written (`Anime`, `Episode`, `Manga`, `Chapter`, `EntrySummary` already exist). operations/: documents + typed calls; exports must end in _QUERY/_MUTATION/_SUBSCRIPTION, so the live spec validates them. operations/entries.ts already has ANIME_QUERY, EPISODES_QUERY, MANGA_QUERY, CHAPTERS_QUERY (database reads only; the refresh mutations come in this stage).
  - src/composables/: useSources/useSourceFilters/useSourceBrowse, useEntries.ts (`useAnime`/`useEpisodes`, `useManga`/`useChapters`, staleTime 30 s), useExtensions.ts, useOpenInExtensions, queryKeys.ts (`anime`, `episodes`, `manga`, `chapters` keys exist). Queries retry only NETWORK/BACKEND_UNAVAILABLE; mutations never retry.
  - src/stores/: backend, events, mediaKind, extensionFilters, pinnedSources (localStorage), sourceBrowse (per source page, session only).
  - src/utils/: filters.ts, sources.ts (`sourceAccess`), format.ts.
  - Components:
    - PageLayout (`fill` gives the last child the remaining height), ErrorNotice, PagePlaceholder;
    - VirtualList (rows, measured, all rendered below 60, `resetKey`): use it for EpisodeList/ChapterList;
    - VirtualGrid (cards: columns from the width, rows virtualized above 60, `end-reached` near the end, a `footer` slot);
    - MediaCard (cover via useAssetUrl with a letter fallback, title, "In library" badge, links to /anime/:id or /manga/:id): reuse it for the Library grid;
    - FilterRenderer/FilterField/FilterSheet, ExtensionRow, TrustDialog.
  - Shared styles in styles/main.css: .btn, .btn-accent, .btn-small, .field, .badge (.warn/.danger/.ok), .dialog*, .flyout, `a` in the accent colour. Tokens in styles/tokens.css; components use tokens only.
  - Routes: /library (LibraryView placeholder), /anime/:id and /manga/:id (AnimeDetailsView/MangaDetailsView placeholders; they set the anime/manga switch from the route).
  - Test helpers in src/test/: mountView, setBackendReady, fixtures (makeStore, makeExtension, installed, makeSource, makePage), filterFixtures, withSetup, fakeSocket.
- Decided (Phase 3 §9): Reka UI primitives with our own styles; @tanstack/vue-virtual for long lists; dark + light themes; a Fluent-like look; native <select> is fine for a plain list of strings. Stage 4: filters only on Search, only the changes from defaults; per-source page state for the session; results deduplicated by id.

Baseline:
- server: `.\gradlew.bat build` is green with 662 tests, 0 failures: core 604 (8 skipped), AndroidCompat 7, ext-host 8, api 43. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. This stage will likely change server/ (updateAnime): then rebuild, and add tests.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass (last run at the start of Stage 3; rerun at the start of this stage).
- app: `pnpm test` gives 19 files, 148 passed + 7 skipped.
  - That's client 15, ws 13, realtime 6, hooks 9, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6, TrustDialog 5, VirtualList 2, ExtensionsView 11, ReposView 8, filters 10, FilterRenderer 8, VirtualGrid 5, BrowseView 6, SourceView 12.
  - The 7 skipped are the live spec.
  - `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- The live spec (operations.live.spec.ts with MADOYOMI_LIVE_URL/TOKEN): 7/7 (the last is a filtered search per kind).
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 6 min).
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat.
  - `.\gradlew.bat :api:shadowJar` builds only the jar. `pnpm tauri dev` and the seed script use the newest jar, so rebuild it after a server change.
  - New server code goes under `madoyomi.*` in `api`; an upstream file edit needs a `// Madoyomi:` comment and an UPSTREAM.md row. The api module's GraphQL tests (e.g. AnimeBrowseApiTest) show how to test a new operation.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio. It sets $script:base and $script:token, and GqlData runs a query.
- Seeding a data dir (the Stage 4 recipe): a scratchpad seed.ps1, run as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> > <log> 2>&1`:
  1. dot-source tools/lib/MadoyomiServer.ps1;
  2. `Start-MadoyomiServer <java> (Resolve-MadoyomiJar <repo> '' -NoBuild) <scratch data dir>`;
  3. add both repos with GqlData (`addExtensionStore(input: {indexUrl, type})`, if `extensionStores` has none of that type);
  4. install AnimeGG, MangaPill and KickAssAnime (`eu.kanade.tachiyomi.animeextension.en.animegg`, `eu.kanade.tachiyomi.extension.en.mangapill`, `eu.kanade.tachiyomi.animeextension.en.kickassanime`) with InstallWithProgress. **`extensions(condition: {pkgName})` returns more than that package**: filter the nodes by `pkgName` yourself before checking `isInstalled`;
  5. set $env:MADOYOMI_LIVE_URL=$script:base and $env:MADOYOMI_LIVE_TOKEN=$script:token, and run `cmd /c "pnpm vitest run src/api/__tests__/operations.live.spec.ts --silent=false 2>&1"` from app/ (`--silent=false` shows the filtered-search report a passing test prints);
  6. Stop-MadoyomiServer.
  One server per data dir: H2 locks it.
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, with MADOYOMI_DATA_DIR exported, output to a scratchpad log). Wait on <dataDir>/logs/sidecar.log for a "healthy after" line **from this session**. The previous session's lines stay in that file until the shell rotates it at start-up, so first wait for the file to change (or count the lines), then give the window a few seconds before the first click.
  - Several unrelated java processes are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi | ? MainWindowHandle -ne 0).CloseMainWindow().
- Screenshots, clicks and typing all WORK. Recreate a scratchpad win.ps1 that:
  - calls SetProcessDPIAware, finds the madoyomi process's MainWindowHandle, ShowWindow(9), SetForegroundWindow, then GetWindowRect;
  - `-Action shot -Out x.png` does a System.Drawing CopyFromScreen of that rect;
  - `-Action click -X -Y` does SetCursorPos(rect.L+X, rect.T+Y) + mouse_event(2) / mouse_event(4);
  - `-Action wheel -X -Y -Delta -2400` does mouse_event(0x0800) (several in a row reach page 2 of a grid);
  - `-Action paste -Text <s>` does Set-Clipboard, then SendKeys `^a`, `^v`;
  - `-Action keys -Text '{ENTER}'` uses SendKeys, which also clears a field with `^a{BACKSPACE}`.

  Coordinates are window-relative physical pixels, read off the screenshot. The window is 1296x839 with its frame. Positions:
  - the sidebar's switch: Anime x≈77 / Manga x≈178, y≈101;
  - Library y≈153, Browse 191, Extensions 229;
  - the page's actions button at about x≈1210, y≈72;
  - on a source page: the tabs at y≈152 (Popular x≈316, Latest 387, Search 454), the search box x≈700, Filters x≈962; the first Browse row at y≈190.

  Text that wraps moves everything below it: take a screenshot before clicking inside a sheet. Reka tabs react to the mousedown of a click. Don't commit screenshots.
- Stage 4 findings that matter here:
  - AnimeGG's own Genre filter 404s on its site, and MangaPill's site returns nothing for Type=Manhwa. Those are the sites, not the UI.
  - A source's empty-query search may return nothing.
  - KickAssAnime is the anime source whose filters work.
  - Covers that fail show a letter. Entries reached from Browse are stored with stable ids (fetchSource* upserts them), so /anime/:id from a card is a real stored entry.
- Reka in jsdom:
  - Tabs change on `mousedown`. Select opens with Enter on its trigger, and an option is picked with a bubbling Enter keydown, once `scrollIntoView`, `hasPointerCapture` and `releasePointerCapture` are stubbed (see FilterRenderer.spec.ts).
  - Collapsible opens on click.
  - Dialogs portal to <body> (mountView attaches there).
  - `CollapsibleContent` keeps its element with `hidden=""`: a `display` rule on it needs a `[hidden] { display: none }` partner.
  - The virtualizers run in jsdom once clientHeight/clientWidth (the viewport), offsetHeight (the rows) and, for VirtualGrid's end check, scrollHeight are stubbed (VirtualList.spec.ts, VirtualGrid.spec.ts).
- graphql-js refuses upstream's schema (two input types without fields), so the live spec builds it with assumeValid: true. Keep that.
- TypeScript 6 loads no @types by default: in app code there's no `process` (see how operations.live.spec.ts reads env) and no Array.prototype.at (lib ES2020). `h()` doesn't take a generic SFC: mount it through `as unknown as Component`, with props and slots. ESLint warns about a second component defined in a spec file: mount the component itself and wire v-model with `"onUpdate:modelValue": (v) => wrapper.setProps({ modelValue: v })`.
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

Tasks (Stage 5):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-details`. Never edit on main. (If feat/phase-3-browse isn't merged yet, stop and tell me.)
1. Server (only what the screens need, and record it):
   - `updateAnime` (and `updateAnimes` if cheap) in upstream's `updateManga` shape, at least for `inLibrary` (with `added_at`);
   - api tests for it;
   - `.\gradlew.bat build` (at least 662 tests plus the new ones) and tools/smoke-api.ps1 -NoBuild (24 steps) after rebuilding the jar;
   - server/UPSTREAM.md ("New Madoyomi code" or a new Phase 3 section), with a `// Madoyomi:` comment on any upstream edit.
2. API: the refresh and library operations in src/api/operations/ and types.ts:
   - `fetchAnimeAndEpisodes` through `gqlResult`, keeping the data when the source failed;
   - `fetchManga` + `fetchChapters`;
   - `updateManga`/`updateAnime` for the library;
   - the library lists (`animes`/`mangas` with `inLibrary: true`).
   Keep the _QUERY/_MUTATION names, and add composables with invalidations (the browse pages' `inLibrary` marks, the library list).
3. Details (step 15), for anime and manga:
   - cover (useAssetUrl), title, author/artist, status, genres, description;
   - refresh from the source (on first open when the entry isn't `initialized`, and by a button);
   - add to / remove from the library;
   - the source's error shown readably, with the stored data kept;
   - a virtualized EpisodeList/ChapterList (VirtualList, 1000+ entries) with a sort (source order, number, date; ascending/descending) and a filter (read/unread or watched/unwatched placeholders until Phase 6, bookmarked, scanlator if cheap);
   - a Seasons anime lists its seasons, which link to their own details;
   - the episode/chapter rows don't play or read yet (Phases 4-5): say so.
4. Library (step 16):
   - a VirtualGrid of MediaCards of the current kind's saved entries;
   - a basic sort (title, date added);
   - an empty state that links to Browse.
5. Tests:
   - component tests for both details views (refresh, the error kept with the data, add/remove, sort and filter, a Seasons entry) and the Library (sort, empty state);
   - an EpisodeList/ChapterList virtualization test with 1000+ rows;
   - keep the route smoke test passing;
   - extend the live spec for the new documents (validation runs on its own), and consider a live refresh of a stored entry.
6. Live check in `pnpm tauri dev` (the Stage 4 seed is fine), by clicks and screenshots:
   - open an entry from a source page for both kinds;
   - see its details and its episode/chapter list (find one with many episodes or chapters and scroll it);
   - add it to the library and see it in Library;
   - remove it;
   - after a backend kill, details and the library still work without reloading the window.
7. Phase doc: §5 ticks (only verified items; "Virtualized grids/lists" and "Covers via backend image proxy" once the library grid, the lists and the details covers are verified), §9 decisions, §10 findings. Update the §8 stage plan if the plan changes.
8. docs/PLAN.md:
   - Tick the Phase 3 milestones only if every phase-doc item behind them is verified. "Repos, Extensions …, Settings screens" won't be after this stage (Settings remains), and "Generic filter and preference renderers" won't be either (PreferenceRenderer is Stage 6). Leave both unticked and say which parts are done.
   - Keep Phase 3 "in progress" in §4 and §8 "First tasks", with the stage number.
   - Update §6 risks and §7 open decisions if this stage decides anything (the mpv window question stays open).
9. README.md status:
   - the warning box (what works now), the Roadmap row for Phase 3, the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list;
   - make it match the phase doc and PLAN.md;
   - also update app/README.md (the structure and the routes table).
10. At the end, write the ready-to-paste prompt for the next stage (Stage 6: Settings and PreferenceRenderer) into NEXT-PROMPT.md in the repo root (overwrite it), and repeat it in your final message.

Verify:
- Checks:
  - `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 148 tests plus the new ones (the live spec skipped);
  - the live spec passes 7/7 (or more) against a seeded server;
  - `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- `pnpm tauri dev`: details for both kinds with a long scrolled list; add to and remove from the library; the Library grid; all of it still working after a backend kill, without reloading the window.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 662 tests plus the new ones), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-details, with only text files. None of these:
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
