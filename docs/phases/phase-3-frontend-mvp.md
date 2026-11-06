# Phase 3 — Frontend MVP

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 2](phase-2-anime-sources.md) · Next: [Phase 4](phase-4-playback.md)

## 1. Goal

Build the Vue 3 UI so a user can do everything from Phases 1–2 without curl: manage repos and extensions (including the trust prompt), browse sources with filters, open details, see episodes/chapters, add to the library and change settings, for both anime and manga. Tauri starts the backend sidecar and the UI connects to it.

## 2. Depends on / inputs

- Phases 1–2: repo/extension/source/anime API endpoints and `/ws`.
- Existing scaffold in `app/`: Vue 3, Vite, TS, Pinia, Vue Router, TanStack vue-query, vitest, ESLint/Prettier.
- Phase 0 S5 sidecar code in `app/src-tauri/src/sidecar.rs` (the dummy spawn; replaced by the real lifecycle in Stage 1).

## 3. Deliverables

- `app/src-tauri/src/sidecar.rs`: spawn the backend, parse the port, health-check it, restart it on crash, and expose the `get_backend_info` command.
- `app/src/api/`: typed client (`fetch` wrapper with auth token + base URL), types mirroring backend models, WebSocket client.
- `app/src/composables/`: vue-query hooks (`useSources`, `usePopular`, `useAnime`, `useEpisodes`, …).
- Screens in `app/src/views/`: Library, Browse (source list), Source (popular/latest/search), Details (anime & manga), Extensions, Repos, Settings.
- Components in `app/src/components/`: `FilterRenderer`, `PreferenceRenderer`, `TrustDialog`, `MediaCard`, `VirtualGrid`, `EpisodeList`, `ChapterList`.
- Component tests in `app/src/**/__tests__/`.

## 4. Detailed steps

### Shell & sidecar
1. Move the sidecar logic into `sidecar.rs`: start the backend (dev: `java -jar` from `server/` build output; prod: bundled JRE in Phase 7), pass `--data-dir` and a random auth token, then wait for `MADOYOMI_PORT`. The current 20 s handshake timeout is too short: the real server took 7–9 s to start on an idle machine and about 60 s under heavy CPU load, and the shell gives up for good at the timeout (phase-1 §10, "exit run"). Wait much longer, and keep showing "starting" rather than failing while the JVM is alive.
2. Health-check loop. On backend exit, emit a Tauri event and restart with backoff.
3. `get_backend_info` → `{ baseUrl, token }`. Vue calls it once at boot.
4. Splash/"starting backend…" state in `App.vue` until the backend is healthy, with an error screen and log path if it never comes up.

### API layer
5. `api/client.ts`: base URL + token header, JSON parsing, typed errors from the backend error model.
6. `api/types.ts`: Repo, Extension, Source, Anime, Episode, Manga, Chapter, Filter, Preference schema.
7. `api/ws.ts`: reconnecting WebSocket. Dispatch events into vue-query invalidations and Pinia stores.
8. vue-query hooks with sensible `staleTime`, and infinite queries for paged source results.

### Navigation & layout
9. App shell: sidebar (Library, Browse, Extensions, Downloads placeholder, Settings), anime/manga toggle, window drag region.
10. Routes: `/library`, `/browse`, `/browse/:sourceId`, `/anime/:id`, `/manga/:id`, `/extensions`, `/repos`, `/settings`, `/settings/source/:id`.

### Screens
11. **Repos:** list, add by URL (type auto-detected, with override), remove, refresh.
12. **Extensions:** available / installed / updates tabs, search, language filter, NSFW toggle, install/update/uninstall with progress; `TrustDialog` shows the package, cert hash and a warning.
13. **Browse:** list installed sources grouped by language; pin favorites.
14. **Source:** Popular / Latest / Search tabs, search box, filter sheet (`FilterRenderer`), infinite `VirtualGrid` of `MediaCard`s.
15. **Details:** cover, title, status, genres, description, refresh, add to library, episode/chapter list with sort and filter (read/unread placeholder until Phase 6).
16. **Library:** grid of saved anime/manga, a basic sort, empty state that links to Browse.
17. **Settings:** general (theme, language), backend (data dir, logs location), source preferences (`PreferenceRenderer`).

### Generic renderers
18. `FilterRenderer`: Header, Separator, Text, CheckBox, TriState (3-state), Select, Sort (field + asc/desc), Group (collapsible). Emits a JSON state the backend understands; supports "reset".
19. `PreferenceRenderer`: List, EditText, Switch, CheckBox, MultiSelect. Saves with `PUT`, using optimistic updates.

### Quality
20. Virtualize long lists (library, source grid, episode lists with 1000+ entries).
21. Image loading: covers load through the backend image proxy (`/api/image?src=&sourceId=`). If Phase 5 isn't done yet, add a minimal proxy now.
22. Tests: `FilterRenderer` and `PreferenceRenderer` unit tests, API client error handling, one route-level smoke test.
23. `pnpm lint`, `pnpm typecheck` and `pnpm test` all pass.

## 5. Checklist

### Shell & sidecar
- [x] `sidecar.rs`: spawn, port handshake, token (Stage 1)
- [x] Health check + restart on crash (Stage 1)
- [x] `get_backend_info` command (Stage 1)
- [x] Startup splash + failure screen (Stage 1)

### API layer
- [ ] Typed `client.ts` with error model
- [ ] `types.ts` for all backend models
- [ ] Reconnecting WebSocket + query invalidation
- [ ] vue-query hooks incl. infinite queries

### Navigation
- [ ] App shell + sidebar + anime/manga toggle
- [ ] All routes wired

### Screens
- [ ] Repos screen
- [ ] Extensions screen (available/installed/updates) + `TrustDialog`
- [ ] Browse (source list)
- [ ] Source (popular/latest/search + filters)
- [ ] Details (anime + manga) with episode/chapter list
- [ ] Library
- [ ] Settings + source preferences

### Renderers
- [ ] `FilterRenderer` (all filter types + reset)
- [ ] `PreferenceRenderer` (all preference types)

### Quality
- [ ] Virtualized grids/lists
- [ ] Covers via backend image proxy
- [ ] Component + client tests
- [ ] `pnpm lint`, `pnpm typecheck`, `pnpm test` pass

## 6. Exit criteria

- [ ] Starting from a fresh data dir, a user can add both repos, install and trust one anime and one manga extension, browse/search with filters, open details, see episodes/chapters and add both to the library, all in the UI.
- [ ] Source preferences can be changed in the UI and take effect.
- [x] Killing the backend process shows an error and recovers automatically. (Stage 1, §10: the overlay shows the exit and the countdown, and the app is back on a new port about 11 s later. Rechecked in the exit run.)

## 7. Risks & notes

- Huge libraries and episode lists: virtualize from the start, not later.
- Filter state must round-trip exactly; keep backend + UI tests in sync with shared fixtures.
- Keep the auth token out of logs and URLs (use a header).

## 8. Decisions closed here

- UI component approach (hand-rolled vs. a component library such as PrimeVue/Naive UI). **Decided in Stage 1: headless primitives (Reka UI) with our own styles** (§9).
- Theme/design tokens for the app. **Decided in Stage 1: dark and light, following Windows, Fluent-like tokens** (§9).

### Stage plan (2026-11-06)

Each stage ends in something verifiable and a commit:
1. **Shell and sidecar lifecycle** (steps 1–4): `sidecar.rs` runs the real jar with `--data-dir` and a per-session token, waits for the port while the JVM lives, checks `/health`, and restarts with backoff. `get_backend_info`, the splash, the error screen and the restart overlay; the §8 decisions; the design-token base. *Done* (2026-11-06): Rust tests for the helpers, vitest for the store and the gate, a live `pnpm tauri dev` with kills (§10).
2. **API layer and app shell** (steps 5–10):
   - `api/client.ts`: GraphQL `POST /api/graphql` with the token header, and typed errors from `extensions.code`/`details` and from the REST body `{code, message, details}`;
   - `api/types.ts`; `api/ws.ts`: `graphql-transport-ws`, reconnecting, and re-pointed at the new port after a backend restart;
   - vue-query hooks, including infinite queries for paged source results;
   - the app shell: sidebar, anime/manga toggle, drag region; every route of step 10 as a placeholder; Reka UI added.
   - Verified by client tests against a mocked fetch/WebSocket, a route-level smoke test, and a live query in `pnpm tauri dev`.
3. **Repos and Extensions** (steps 11–12): repos list/add (type auto-detected, with override)/remove/refresh. Extensions with available/installed/updates tabs, search, language and NSFW filters, install/update/uninstall with live progress (`extensionInstallProgress`), and `TrustDialog` (package, cert hash, warning). Verified live: add yuzono and Keiyoushi, install and trust one extension of each kind.
4. **Browse and Source, with `FilterRenderer`** (steps 13–14, 18, 20–21): the source list grouped by language with pinned favourites; Popular/Latest/Search; the filter sheet; an infinite `VirtualGrid` of `MediaCard`s. Covers load through the backend's thumbnail routes (`/api/v1/anime/{id}/thumbnail`, upstream's manga thumbnail), which already apply the source's headers, so step 21 needs no new proxy. Verified with `FilterRenderer` unit tests on shared fixtures, and live with a filtered search for both kinds.
5. **Details and Library** (steps 15–16, 20): details for anime and manga, with a virtualized `EpisodeList`/`ChapterList` (1000+ entries), sort and filter; add to library; the Library grid with a sort and an empty state.
6. **Settings and `PreferenceRenderer`** (steps 17, 19): general settings (theme System/Dark/Light, language), backend settings (data dir, log folder), and source preferences saved with optimistic updates. Verified by renderer unit tests and a live preference change that takes effect.
7. **Exit run** (steps 22–23 and §6): every exit criterion from a fresh data dir, the remaining tests, a fix round, and the phase closed in PLAN.md and README.md.

## 9. Decision record

### Stage 1 (2026-11-06)

Decided by the user:
- **UI components: headless primitives plus our own styles.** Reka UI (unstyled, accessible Vue primitives: Dialog, Select, Tabs, Checkbox with an indeterminate state, Collapsible, Switch, Popover) styled only through our design tokens, and `@tanstack/vue-virtual` for long lists. Each is added in the stage that first needs it (Reka UI in Stage 2). The look stays ours, and keyboard handling, focus traps and ARIA come from the library. PrimeVue and Naive UI were rejected as heavier and opinionated in look. Fully hand-rolled was rejected because it means writing and testing the accessibility of dialogs, selects and tabs ourselves.
- **Theme: dark and light from day one, following Windows.** The default comes from `prefers-color-scheme`. `<html data-theme="dark|light">` overrides it; the Settings screen (Stage 6) sets that attribute (System/Dark/Light).
- **Look: Windows 11/Fluent-like.** Segoe UI Variable, neutral layered surfaces (`#202020`/`#2b2b2b` dark, `#f3f3f3`/`#fbfbfb` light), 4/8 px radii, subtle borders, one accent (Windows' default `#60cdff` dark / `#005fb8` light; following the user's Windows accent is left for Settings), compact density (32 px controls). The tokens are in `app/src/styles/tokens.css`. Components use only these variables, never raw colours.

Decided in the stage (defaults, not asked):
- **Data dir: `%APPDATA%\Madoyomi`**, the server's own default since Phase 1, so a server started by hand and the app share one place. Env `MADOYOMI_DATA_DIR` overrides it (a fresh dir for tests). Dev builds use the same location, so stop `tools/run-server.ps1` sessions on the same dir first (H2 holds a file lock); `run-server.ps1` itself uses `tools/dev-data/`.
- **One token per app session**, 32 random bytes as hex (`getrandom`), passed as `MADOYOMI_TOKEN`, kept across restarts. Only the port changes, so the UI calls `get_backend_info` again after each restart. The token is never logged and never sent in an event. Only `get_backend_info` returns it, and the UI keeps it in memory.
- **Restart policy.** A start waits for the port while the JVM is alive (up to 5 min, `slow` after 30 s), then for the first good `/health` (up to 60 s). A healthy backend is checked every 5 s (2 s timeout); 4 misses in a row count as a hang, and it is killed. An exit is noticed at once, because its stdout closes. The backoff is 1, 2, 4, 8 s (capped at 30 s). After 5 failed starts in a row the supervisor gives up and waits for Retry. A run that was healthy for 60 s starts a new streak. A missing jar or java, or exit code 2 (usage error), gives up at once, because a restart can't fix it.
- **Shutdown.** On app exit the shell closes the backend's stdin (the server shuts down and H2 closes the database), and kills it after 5 s. A crash of the shell is still covered by the Job Object, now one per app session for every backend it starts.
- **`/health` over plain `std::net` HTTP/1.0**, not an HTTP crate: the server then closes the connection after the response (and `Content-Length` ends the read anyway). One dependency fewer.
- **The shell's log is `<dataDir>/logs/sidecar.log`**: the backend's stdout and stderr plus the shell's lifecycle lines, with UTC timestamps. The previous session's file is kept as `sidecar.1.log`, and it rotates at 10 MB. It is the only record of failures before the server's logger starts (a bad JVM, a usage error). The server's own log is `madoyomi.log` in the same folder. The JVM's working directory is that folder too, so an `hs_err_pid*.log` lands there and not in the repo.
- **Launch resolution, with Phase 7's paths in place.**
  - Java: `MADOYOMI_JAVA`, else in release builds `<resources>/jre/bin/java.exe`, else in debug builds `tools/bin/jdk-21/bin/java.exe`, else `java` from PATH.
  - Jar: `MADOYOMI_SIDECAR_JAR`, else in release builds `<resources>/server/Madoyomi-Server.jar`, else in debug builds the newest `server/api/build/Madoyomi-Server-*.jar`.
  - The launch is always `java -jar`, so the manifest's `Add-Opens` apply.
  - Phase 7 only has to put the JRE and the jar at those resource paths.
- **Shell API.** Commands `get_backend_status` (snapshot), `get_backend_info` (`{ baseUrl, token }`; waits while the backend starts or restarts, and fails once it is `failed`), `restart_backend` (Retry, or skipping the backoff), and `open_backend_logs` (shows `sidecar.log` in Explorer). The event is `backend-status` with the same snapshot (`seq, state, attempt, elapsedMs, slow, baseUrl, pid, serverVersion, error, exitCode, retryInMs, dataDir, logDir`), sent on every change and once a second while starting. `seq` lets the UI drop a snapshot older than an event it has already applied. The Phase 0 `get_backend_url` is gone.
- **UI gate.** Until the backend is first ready, `BackendGate` shows a full-screen splash or failure. After that, the views stay mounted and a restart shows an overlay over them, so screen state survives a backend crash.
- **Browser mode.** Plain `pnpm dev` renders the app with a "no backend" banner. With `VITE_MADOYOMI_URL` and `VITE_MADOYOMI_TOKEN` (in the gitignored `app/.env.local`) it uses a running server instead, e.g. one from `tools/run-server.ps1` (CORS allows `http://localhost:1420`).

## 10. Findings log

### Stage 1 (2026-11-06)

- **Baseline before the stage:** `pnpm lint` and `pnpm typecheck` clean; `pnpm test`: 1 file, 4 tests; `cargo build` OK; `cargo test`: 0 tests. **After:** `pnpm test`: 3 files, 19 tests (HomeView 4, backend store 9, BackendGate 6); `cargo test`: 14 tests; `cargo clippy --all-targets` clean; `pnpm lint`, `pnpm typecheck` and `pnpm format:check` clean. Nothing in `server/` changed; `tools/smoke-api.ps1 -NoBuild` still passes 24/24.
- **Startup times** (`pnpm tauri dev`, real jar, measured from the spawn in `sidecar.log`). The machine was not idle: Gradle and IDE JVMs were running.

  | Run | Port line | Healthy |
  |---|---|---|
  | First start, fresh `%APPDATA%\Madoyomi` (DB created, migrations) | 12.3 s | 12.5 s |
  | Warm starts | 9.9–11.1 s | 10.1–11.3 s |
  | Restarts after a kill | 9.5–12.2 s | 9.8–12.4 s |

  The first good `/health` follows the port line within 0.2–0.3 s. The Phase 0 shell's 20 s limit was close even on a warm machine.
- **Restart behaviour, measured live** (`Stop-Process -Force` on the backend's java pid):
  - The exit is seen at once (stdout closes; Windows reports exit code -1). The restart started 1 s later, and the backend was healthy on a new port about 11 s after the kill. No user action was needed.
  - Kills in a row (each within 60 s of healthy) gave 1, 2, 4 and 8 s delays. The fifth failed start gave up ("not restarting again; waiting for a retry from the UI").
  - The window, checked by screenshot: the splash with elapsed seconds; the home screen with `Madoyomi-Server v0.1.0 · http://127.0.0.1:<port>` (shown only once `get_backend_info` has answered); the overlay "The backend stopped", with the exit message, "Restarting in 1 s…", the log folder, and Restart now / Open logs; then "Starting the backend… attempt 2"; then the home screen again, on the new port.
- **Token and data dir.** The server never printed its "MADOYOMI_TOKEN is not set" warning, so the token arrived. `POST /api/graphql` without a token, or with a wrong one, got 401, and `/health` answered without one. `%APPDATA%\Madoyomi` got the database, settings, extensions and `logs/` (`madoyomi.log`, `sidecar.log`). A request with the right token is first made from the UI in Stage 2.
- **Shutdown.** Closing the window closed stdin: "backend stopped (exit code 0)" within 0.1 s. Killing the app process (`Stop-Process -Name madoyomi -Force`) took the java process down with it, through the Job Object.
- **Missing jar.** With `MADOYOMI_SIDECAR_JAR` pointing at a file that doesn't exist, the failure screen appeared at once ("server jar not found: MADOYOMI_SIDECAR_JAR is …", the log folder, Retry, Open logs), with no restart loop. The first version passed the path to `java -jar` and would have burned through five restarts; it is now checked before the spawn.
- **Output decoding.** The JVM writes stdout in the Windows code page, not UTF-8. The Phase 0 reader (`BufRead::lines`) stopped at the first invalid byte and would have left the pipe full, blocking the backend. Lines are now decoded leniently (`read_until` + `from_utf8_lossy`), with a test.
- **Not checked by a click.** I couldn't press the Retry and Open logs buttons in the live window. They are covered by the vitest tests (which check that they call `restart_backend` and `open_backend_logs`) and by the Rust code path. A human check is part of the exit run.