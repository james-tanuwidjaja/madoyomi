Continue Phase 3 (docs/phases/phase-3-frontend-mvp.md), Stage 2: the API layer and the app shell (steps 5-10, the "API layer" and "Navigation" checklists). Follow CLAUDE.md first: read docs/PLAN.md, the Phase 3 doc (all of it: §4 steps, §5 checklist, §6 exit criteria, §8 stage plan, §9 decisions, §10 findings), server/UPSTREAM.md ("Startup contract" and "API (Phase 1 Stage 7)" for the error model), the Phase 2 doc's §4 step 16 and §9 "Stage 4" (the anime GraphQL operations), README.md (status box, Roadmap, decisions, the NOTE on pnpm tauri dev) and app/README.md (the shell commands and event). Never commit. Tick checklist items only once they are done and verified. Plan the stage briefly, then implement it. Ask me only about decisions that are genuinely mine.

Where things stand (Phases 0-2 done; Phase 3 Stage 1 done on branch feat/phase-3-shell):
- server/ is a Suwayomi-Server fork @7b061be (Madoyomi-Server v0.1.0), built as server/api/build/Madoyomi-Server-v0.1.0.jar. Every upstream edit has a // Madoyomi: comment and is listed in server/UPSTREAM.md. Stage 1 changed nothing in server/.
- The API (server/UPSTREAM.md "Startup contract"):
  - GraphQL at POST /api/graphql with `Authorization: Bearer <token>`.
  - Subscriptions use graphql-transport-ws on ws://127.0.0.1:<port>/api/graphql?token=<token>. The server negotiates NO subprotocol, so a client must not require one to be echoed. A browser WebSocket created with a protocols argument fails the handshake when the server doesn't echo it, and the graphql-ws library sets 'graphql-transport-ws' by default. Check this before picking a library.
  - REST only for binaries: manga thumbnails and pages, extension icons, /api/v1/anime/{id}/thumbnail. GET requests (img src) may pass ?token=.
  - Errors: GraphQL errors carry extensions {code, details}; REST errors are the body {code, message, details}. Codes: NOT_FOUND, BAD_REQUEST, UNTRUSTED, LOAD_FAILED, SOURCE_FAILED (details.reason CLOUDFLARE), EXTENSION_INCOMPATIBLE, INTERNAL, UNAUTHORIZED, and others.
  - Anime: animeSources (filters, preferences, isConfigurable, supportsLatest), fetchSourceAnime(POPULAR|LATEST|SEARCH, filters: [FilterChange]), fetchAnimeAndEpisodes, anime/animes/episodes, fetchEpisodeVideos/fetchHosterVideos/resolveEpisodeVideo, updateAnimeSourcePreference. Manga: upstream's operations.
  - Repos and extensions: addExtensionStore, fetchExtensions, updateExtension (install/update/uninstall), trust; subscriptions extensionInstallProgress and extensionStoreRefreshed.
  - Filters and preferences use upstream's GraphQL unions (one renderer for anime and manga). GraphQL Long values are the LongString scalar.
- The shell (app/src-tauri/src/sidecar.rs, Stage 1):
  - It runs the newest server/api/build/Madoyomi-Server-*.jar with tools/bin/jdk-21, a per-session random token (MADOYOMI_TOKEN) and --data-dir %APPDATA%\Madoyomi.
  - It waits for the port while the JVM lives (5 min cap), checks /health every 5 s, and restarts after a crash with 1/2/4/8 s backoff; after 5 failed starts in a row it waits for Retry.
  - Commands: get_backend_status, get_backend_info -> {baseUrl, token}, restart_backend, open_backend_logs. Event: backend-status (snapshot with seq, state, baseUrl, error, ...).
  - The port changes after every restart; the token doesn't.
  - Logs: <dataDir>/logs/sidecar.log (shell + backend output) and madoyomi.log (server).
  - Env overrides: MADOYOMI_SIDECAR_JAR, MADOYOMI_JAVA, MADOYOMI_DATA_DIR.
- The UI:
  - app/src/stores/backend.ts (Pinia useBackendStore: mode tauri|external|browser, status, info {baseUrl, token}, ready, hasBeenReady, init/retry/openLogs) is where the API client gets its base URL and token. Re-read them after every restart (watch `info`).
  - app/src/components/BackendGate.vue wraps <RouterView/> in App.vue: splash before the first ready, then a restart overlay over the mounted views.
  - Design tokens are in app/src/styles/tokens.css (Fluent-like, dark + light following Windows, data-theme override); main.css has .btn/.btn-accent.
  - HomeView.vue is a placeholder; the only route is /.
  - Plain `pnpm dev` can use a running server through VITE_MADOYOMI_URL + VITE_MADOYOMI_TOKEN in app/.env.local.
- Decided (Phase 3 §9): Reka UI headless primitives with our own styles (add it this stage), @tanstack/vue-virtual for lists (Stage 4), dark + light themes, a Fluent-like look.

Baseline:
- server: `.\gradlew.bat build` green with 662 tests, 0 failures: core 604 (8 skipped, live/scan tests behind env vars), AndroidCompat 7, ext-host 8, api 43. Count them from server/{core,AndroidCompat,ext-host,api}/build/test-results/test/*.xml after a full build. The count must not drop. Rebuild only if server/ changes.
- tools/smoke-api.ps1 -NoBuild: 24 steps pass.
- app: `pnpm test` gives 3 files, 19 tests (HomeView 4, stores/backend 9, BackendGate 6). `pnpm lint`, `pnpm typecheck` and `pnpm format:check` are clean.
- app/src-tauri: `cargo test` 14 tests; `cargo clippy --all-targets` clean.
- Record all of these at the start as this stage's baseline.

Gotchas:
- Server build: `$env:JAVA_HOME='D:\Projects\madoyomi\tools\bin\jdk-21'; cmd /c "cd /d D:\Projects\madoyomi\server && .\gradlew.bat build --console=plain > <scratchpad log> 2>&1"`, in the background (about 6 min).
  - Use absolute paths: a relative JAVA_HOME silently falls back to JVM 11.
  - Write .\gradlew.bat, not gradlew.bat.
  - `.\gradlew.bat :api:shadowJar` builds only the jar.
- From the PowerShell tool, stdin is the null device, so a server started there exits at once. Start-MadoyomiServer in tools/lib/MadoyomiServer.ps1 redirects stdio. `pnpm tauri dev` is fine: the shell pipes stdin itself.
- Live app checks:
  - Run `pnpm tauri dev` in the background (Bash tool, output to a scratchpad log), then wait on <dataDir>/logs/sidecar.log for "healthy after".
  - Editing src-tauri while it runs makes it rebuild and relaunch.
  - Several unrelated java processes (Gradle, IDE) are usually running: kill only the pid from sidecar.log ("pid N listening").
  - Close the app cleanly with (Get-Process madoyomi).CloseMainWindow().
  - Use MADOYOMI_DATA_DIR=<scratchpad dir> for a throwaway data dir; %APPDATA%\Madoyomi now holds the dev data from Stage 1.
  - To see the window: Stage 1 took screenshots with a small PowerShell script (System.Drawing CopyFromScreen of the madoyomi window rect, after SetProcessDPIAware/SetForegroundWindow) into the scratchpad, then read the PNG. Recreate it there; don't commit it.
- Run long scripts as `powershell -NoProfile -ExecutionPolicy Bypass -File <script> *> <scratchpad log>` in the background. tools/smoke-api.ps1 -NoBuild checks the backend end to end (24 steps).
- PowerShell:
  - variables are case-insensitive (a local can be a parameter);
  - a function's pipeline output is its return value;
  - PS 5.1 has no `??`;
  - with $ErrorActionPreference='Stop', any native stderr line aborts the script;
  - keep .ps1 files ASCII;
  - write files with [IO.File]::WriteAllText + UTF8Encoding($false) or the Write tool;
  - backslashes inside perl -e / sed replacements get mangled (\t, \c): edit such lines with the Edit tool.
- The token must stay out of logs and URLs, except where the contract needs ?token= (img src, WebSocket). Never print it in test output or docs.
- Adding npm packages (reka-ui, maybe a GraphQL WebSocket client) needs network access; pnpm-lock.yaml changes are expected and are text.
- Python and gh are not installed.

Tasks (Stage 2):
0. FIRST, before any edit: `git switch main`, `git pull`, then `git switch -c feat/phase-3-api`. Never edit on main. (If feat/phase-3-shell isn't merged yet, stop and tell me.)
1. `app/src/api/client.ts` (step 5):
   - GraphQL POST with the token header; base URL and token from useBackendStore, re-read after a restart;
   - typed errors (an ApiError with code, message, details, path) from GraphQL `extensions` and the REST error body, plus network errors and a request made while the backend isn't ready;
   - helpers for binary URLs (thumbnails, icons) with ?token=.
2. `app/src/api/types.ts` (step 6): Repo/ExtensionStore, Extension, Source (anime and manga), Anime, Episode, Manga, Chapter, Video, the Filter/FilterChange and Preference unions, error codes; LongString as string. Choose hand-written types or codegen from the server's introspection (e.g. graphql-codegen), and record why in §9. That choice is yours unless it adds a build step I'd have to run.
3. `app/src/api/ws.ts` (step 7): graphql-transport-ws (connection_init, subscribe/next/complete, ping/pong) with ?token= and no required subprotocol. It reconnects with backoff, re-points at the new baseUrl after a backend restart, and dispatches extensionInstallProgress / extensionStoreRefreshed into vue-query invalidations and Pinia.
4. vue-query hooks in `app/src/composables/` (step 8), with sensible staleTime and infinite queries for paged source results. Browsing is a mutation (fetchSourceAnime / fetchSourceManga) returning a page, so wrap it in an infinite query.
5. App shell (steps 9-10):
   - a sidebar (Library, Browse, Extensions, Downloads placeholder, Settings);
   - an anime/manga toggle (a Pinia store, persisted);
   - a window drag region (a custom title bar needs core:window permissions and decorations off; keeping the native title bar is fine, so decide and record);
   - every route of step 10 as a placeholder view;
   - Reka UI added, everything styled with tokens.css.
6. Tests: client error handling (GraphQL error, REST error body, 401, network failure, a restart between requests), the WebSocket protocol and reconnect against a fake socket, a hook test, and one route-level smoke test (step 22 part).
7. Live check in `pnpm tauri dev`: a view shows real data from an authenticated query (e.g. extension stores or sources), the first positive token check from the UI. A subscription receives an event (e.g. add a repo, or extensionStoreRefreshed). After killing the backend's java, the next query and the subscription work again on the new port.
8. Phase doc: §5 ticks (only verified items), §9 decisions, §10 findings. Update the §8 stage plan if the plan changes.
9. docs/PLAN.md: tick the Phase 3 milestone "Typed API client + WebSocket" only if every phase-doc item behind it (the four "API layer" items) is verified. Keep Phase 3 "in progress" in §4 and §8 "First tasks" with the stage number. Update §6 risks and §7 open decisions if this stage decides anything (the mpv window question stays open).
10. README.md status: the warning box (what works now), the Roadmap row for Phase 3, the NOTE about pnpm tauri dev if behaviour changes, the commands tables for any new or changed command, and the "Decided so far in Phase 3" list. Make it match the phase doc and PLAN.md. Also update app/README.md (structure: api/, composables/, routes).
11. At the end, write the ready-to-paste prompt for the next stage (Stage 3: Repos and Extensions with TrustDialog) into NEXT-PROMPT.md in the repo root (overwrite it) and repeat it in your final message.

Verify:
- `pnpm lint`, `pnpm typecheck`, `pnpm format:check` and `pnpm test` pass, with at least 19 tests plus the new ones; `cargo build`, `cargo test` (at least 14) and `cargo clippy --all-targets` pass.
- `pnpm tauri dev`: the shell shows the sidebar and routes; a real authenticated query and a subscription work; after a backend kill they work again without reloading the window.
- If anything in server/ changed: `.\gradlew.bat build` is still green (at least 662 tests), tools/smoke-api.ps1 -NoBuild still passes (24 steps), and server/UPSTREAM.md lists the change.
- docs/PLAN.md: every Phase 3 milestone is ticked if and only if its phase-doc items are verified, and the phase status in §4/§8 matches the phase doc.
- README.md's status box, Roadmap row and decisions match the Phase 3 doc and PLAN.md.
- NEXT-PROMPT.md in the repo root holds the next stage's prompt.
- `git status`: on branch feat/phase-3-api, only text files. No jars, APKs, .pb files, fonts, test images, DB files, hs_err logs, heap dumps, screenshots, node_modules, target/, dist/, .env.local, tools/compat-test/out or tools/dev-data.

Standing rules:
- Start every stage by switching to a new feature branch off an up-to-date main (git switch main, git pull, git switch -c feat/phase-3-<topic>) before any edit. Never edit on main.
- Work one stage at a time.
- Never commit (no git commit/merge/rebase/cherry-pick/revert). Never commit or stage binaries, APKs, jars, .pb files or heap dumps.
- Ask me only about decisions that are genuinely mine.
- Every stage updates README.md's status (status box, Roadmap row, changed tables, decisions) and docs/PLAN.md's milestones (tick what the phase doc verifies, update phase status, risks and open decisions), together with the phase doc and, for server changes, server/UPSTREAM.md.
- At the end of the stage, stop and report what changed, the decisions and findings, how you verified it (test counts against the baseline), a one-line commit message and a PR title.
- Then write a ready-to-paste prompt for the next stage into NEXT-PROMPT.md in the repo root (overwrite it) and repeat it at the end of your report. Use this same format: current state, baseline test counts, gotchas, the tasks with the branch switch as task 0 and the README status / PLAN.md tick tasks, verification steps including the README and PLAN.md checks, and these standing rules. Tell that agent to do the same.
- Then wait for me to commit or merge.
