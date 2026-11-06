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
- [x] Typed `client.ts` with error model (Stage 2: `ApiError` with `code`, `details`, `path`, `status` from GraphQL `extensions`, the REST body, a failed fetch and a request while not ready; token-bearing asset URLs)
- [x] `types.ts` for all backend models (Stage 2: hand-written, every document validated against the live schema by `operations.live.spec.ts`)
- [x] Reconnecting WebSocket + query invalidation (Stage 2: our own `graphql-transport-ws` client in `ws.ts`, no subprotocol; verified live across a backend kill)
- [x] vue-query hooks incl. infinite queries (Stage 2: `composables/`, `useSourceBrowse` pages a browse mutation)

### Navigation
- [x] App shell + sidebar + anime/manga toggle (Stage 2: Reka `ToggleGroup`, persisted; the native title bar is the drag region)
- [x] All routes wired (Stage 2: every step-10 route as a placeholder, plus `/downloads` and not-found; route-level smoke test)

### Screens
- [x] Repos screen (Stage 3: list by kind with the stored URL, count, last refresh, last error and key trust; add by URL with the type detected or chosen; remove after a confirmation; refresh per kind or all; verified live on a fresh data dir, §10)
- [x] Extensions screen (available/installed/updates) + `TrustDialog` (Stage 3: Reka tabs, search, language, "Show 18+", install/update/uninstall with live steps and readable errors, compat issues, a virtualized list; the trust prompt shown, used and verified live, §10)
- [ ] Browse (source list)
- [ ] Source (popular/latest/search + filters)
- [ ] Details (anime + manga) with episode/chapter list
- [ ] Library
- [ ] Settings + source preferences

### Renderers
- [ ] `FilterRenderer` (all filter types + reset)
- [ ] `PreferenceRenderer` (all preference types)

### Quality
- [ ] Virtualized grids/lists (Stage 3: `VirtualList` for the extension list, 1469 Keiyoushi rows; the source grid and episode/chapter lists follow in Stages 4–5)
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
   - *Done* (2026-11-07): vitest for the client, the protocol, the realtime dispatch, the hooks and the routes; a live-only spec against the real server (every document validated, the token, a subscription event); a live `pnpm tauri dev` with a backend kill (§10). The Repos and Browse placeholders already show real data, and Repos has a "Refresh all" and the live events. Stage 3 builds on them.
3. **Repos and Extensions** (steps 11–12): repos list/add (type auto-detected, with override)/remove/refresh. Extensions with available/installed/updates tabs, search, language and NSFW filters, install/update/uninstall with live progress (`extensionInstallProgress`), and `TrustDialog` (package, cert hash, warning). Verified live: add yuzono and Keiyoushi, install and trust one extension of each kind.
   - *Done* (2026-11-07): component tests for both screens, `TrustDialog` and `VirtualList`, the type-detection tests, and the live spec's new check that the server refuses a repo of the wrong kind with its packages.
   - Live in `pnpm tauri dev` from a fresh data dir, driven by clicks and pasted text (§10): both repos added, MangaPill and AnimeGG installed with their steps shown, and the trust prompt reached by removing yuzono. Then a backend kill, after which uninstall and install still worked.
   - Nothing in `server/` changed.
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

### Stage 2 (2026-11-07)

Decided in the stage (defaults, not asked):
- **Hand-written types, no codegen.** `api/types.ts` has the fields the UI asks for, named as in the server's Kotlin types. Codegen would need a running server or a committed schema dump, and a rerun whenever the schema changes, which is a build step for every developer. Instead, every operation document lives in `api/operations/` and **`src/api/__tests__/operations.live.spec.ts`** checks them against the real server. It introspects the schema and runs graphql-js's `validate()` on every document. It also runs the real client with and without the token, and receives `extensionStoreRefreshed` through the real WebSocket client. It is skipped unless `MADOYOMI_LIVE_URL` and `MADOYOMI_LIVE_TOKEN` are set, like the server's env-gated live tests. `graphql` is a devDependency for this spec only.
- **Union fields are aliased by kind.** Upstream's `Filter` and `Preference` unions have fields of the same name and different types (`default`, `currentValue`), which one selection can't hold. They are `selectDefault`, `checkBoxDefault`, `listCurrentValue`, … in the documents and in `types.ts`.
- **Our own `graphql-transport-ws` client (`api/ws.ts`, ~250 lines), not the `graphql-ws` library.** That library always asks for the `graphql-transport-ws` subprotocol. The server negotiates none, so the browser fails the handshake, and working around it needs a wrapper class anyway. The protocol is small:
  - one socket for every subscription, `new WebSocket(url)` with no protocols argument, the token in `?token=`;
  - `connection_init` → `connection_ack` (10 s timeout) → `subscribe` for each active operation, again after every reconnect;
  - it answers `ping` with `pong`, pings every 20 s itself, and treats a missing `pong` (10 s) as a dead socket;
  - backoff 0.5, 1, 2, 4, 8, 10 s, reset by an ack;
  - `setEndpoint(url)` re-points at once (a restart's new port), and `setEndpoint(null)` closes and waits while the backend is down.
- **One subscription of each event for the app's lifetime** (`api/realtime.ts`, started in `main.ts`), with no `pkgName`/`type` filter.
  - The events go into a Pinia store (`stores/events.ts`: the latest install step per package, the last 20 events, the socket state) and invalidate queries: a repo refresh invalidates the repos and that kind's extensions; a finished install (`INSTALLED`/`FAILED`) invalidates that kind's extensions and sources.
  - The server replays nothing, so every **re**connect invalidates the repo, extension and source lists. Views that want progress for one package read the store, and nothing subscribes per view.
- **Errors.** `ApiError {code, message, details, path, status, kind}`.
  - A body with `errors[]` is GraphQL at any status (`BAD_REQUEST` when an error has no code). `{code, message, details}` is the REST body, the token filter's 401 included. Anything else is `BAD_RESPONSE`.
  - Two more client codes: `NETWORK` (no answer) and `BACKEND_UNAVAILABLE`, for a request while the backend isn't ready. That request fails at once and isn't held back: the hooks are disabled while not ready anyway, and a held request would hang a click.
  - The token never appears in an error message.
- **Retry and refetch.**
  - A query retries only `NETWORK`/`BACKEND_UNAVAILABLE` (3 times, 2/4/8 s). A server code is an answer, so it isn't retried. Mutations never retry.
  - When the backend is ready on a new base URL, the queries in error state are refetched.
  - `refetchOnWindowFocus` is off app-wide: it's a desktop app, and a browse refetch would hit the source's site.
- **Hooks** (`composables/`) take refs or getters, and are enabled only while the backend is ready.
  - staleTime: repos 60 s, extensions 30 s, sources 5 min, filters `Infinity` (an extension update invalidates `sources`, and the filters with it), entries 30 s.
  - `useSourceBrowse` is an infinite query over `fetchSourceAnime`/`fetchSourceManga`: page N+1 while the last page says `hasNextPage`, with the kind, source, type, query and filters in the key, staleTime 5 min. It isn't aborted when the view goes away: the server has done the work, and the page stays cached.
- **Asset URLs.** `api.assetUrl(path)` adds `?token=` only to the backend's own server-relative routes (thumbnails, extension icons). An absolute URL (a source's own image) is returned unchanged and never gets the token.
- **The native Windows title bar stays**, and it is the window's drag region. It keeps Snap Layouts, the caption buttons and their accessibility. A custom bar would need `decorations: false`, `core:window` permissions and a hand-made Snap Layouts flyout, which Tauri doesn't provide. Nothing in `src-tauri` changed.
- **Shell layout.** A Fluent-like navigation pane: the name, then the anime/manga switch (Reka `ToggleGroup` as a segmented control), then Library, Browse, Extensions and Downloads. Settings and a footer sit at the bottom. The footer has the app version, the server version and URL, and the live-updates dot (it takes over the Stage 1 placeholder home, whose tests moved to `ShellFooter.spec.ts`). The page sits on a raised layer with a rounded top-left corner, as Windows 11 apps do.
- **The anime/manga switch** is a Pinia store kept in `localStorage` (`madoyomi.kind`, default anime).
  - `/anime/:id` and `/manga/:id` set it from their route.
  - `/browse/:sourceId` and `/settings/source/:id` use the current kind.
  - Switching the kind on one of those four routes goes to that section's list (`/library`, `/browse`, `/settings`), because the page belongs to one kind.
- **Routes.** `/` redirects to `/library`. Besides step 10's routes there are `/downloads` (the sidebar's placeholder, Phase 6) and a not-found page. Repos opens from a button on Extensions, and the sidebar marks Extensions there. Each placeholder names the stage that builds it.
  - Repos already lists the real repos, with "Refresh all" and the live events.
  - Browse already lists the real sources by language.
  - The later stages replace both.

### Stage 3 (2026-11-07)

Decided in the stage (defaults, not asked):
- **No server change.** Everything the two screens need was already in the API (Phase 1 step 17), so `server/` and `server/UPSTREAM.md` are untouched.
- **The repo type is detected in the UI, with no server change.** `RepoService.add` reads either kind's index format, then checks every package against the kind it was given. A wrong kind fails with `REPO_TYPE_MISMATCH` and `details.packages` before anything is written. So "Detect the type" (`addRepoDetectingKind` in `composables/useExtensions.ts`):
  - first tries anime if the URL contains "anime" (yuzono's `anime-repo`), otherwise manga (the server's default);
  - on that one error, tries the other kind once;
  - doesn't retry the other `REPO_TYPE_MISMATCH`, "already added as a manga repo", which has no `packages`;
  - doesn't retry a kind the user chose.

  A wrong guess costs one extra download of the index. The live spec checks against the real server that this error carries the packages. The format (Auto/JSON/Protobuf) is an advanced option, Auto by default.
- **Repos are matched by the URL the server stores** (Stage 2's finding). After an add, the form says "Added" or "Updated" by comparing that URL with the list, and shows the stored URL when it differs from the one typed.
- **Refresh is per kind, not per repo.** `fetchExtensions` refreshes a whole kind, because pruning listings only runs when every repo of the kind fetched. Each kind's group has its own "Refresh anime/manga repos", next to "Refresh all".
  - A single-repo refresh would need a server change for little gain: there is one repo per kind so far.
  - A re-add of the repo isn't a refresh either: it would trust a changed signing key, which a refresh must never do.
- **Removing a repo asks first** (Reka `AlertDialog`). The prompt says what happens: its uninstalled listings leave, and installed extensions signed with its key stop loading until they are trusted or the repo is re-added.
- **The Local source's fake extension (`eu.kanade.tachiyomi.source.local`) is left out of the extension lists.** It is built in, not from a repo. Browse still shows its source; Stage 4 treats source id `0` as built in.
- **Tabs and filters.**
  - Available, Installed and Updates (Reka `Tabs`), with counts. Installed sorts what needs attention first: untrusted, then updatable.
  - Search on the name and package. Native `<select>`s for the language ("Multi-language" for `all`), and for the repo type and format. A plain list of strings needs nothing more, and Reka `Select` is left for `FilterRenderer` (Stage 4).
  - **"Show 18+"** (Reka `Switch`) is off by default and kept in `localStorage` (`madoyomi.showNsfw`). It only hides `NSFW` extensions under Available: what you installed always shows. `MIXED` shows with a "Some 18+" badge.
  - The tab, search and language last for the session (`stores/extensionFilters.ts`). The search starts over when the anime/manga switch changes.
  - When a tab has no match, it names the tab that has one ("1 under Installed"), because an extension leaves Available the moment it's installed.
- **Progress and errors per extension.**
  - The steps come only from the events store (`installProgress[pkg]`: Downloading, Verifying the signature, Converting).
  - Each action's own state (pending, error, result) comes from vue-query's mutation cache (`useMutationState` on the `extensionAction` key). It outlives the view, so an install keeps showing after you leave Extensions and come back.
  - A new action clears the package's old step first (`events.clearInstall`), so an earlier `FAILED` doesn't linger.
  - A row shows the action's own error, or else a `FAILED` step's code and message.
- **Readable errors.** `api/errorText.ts` maps each server and client code to one sentence, and `ErrorNotice` shows it above the code and the server's message. `ErrorNotice`'s "Try again" now shows only when the parent passes `@retry`; a form error has nothing to retry.
- **The trust prompt (`TrustDialog`, Reka `Dialog`)** shows:
  - the extension, package, version, and the repo (or that no added repo lists it any more);
  - the certificate's SHA-256, with Copy (it selects the text if the clipboard is refused);
  - a warning: a trusted extension runs inside the backend with your rights, and trusting the certificate also trusts every other extension it signs.

  Trust calls `trustExtension` and closes. Cancel, Escape or a click outside does nothing. Besides the Trust button on an untrusted row, it **opens by itself when an install the user started ends untrusted**, as Mihon prompts.
- **`components/VirtualList.vue`.** It wraps `@tanstack/vue-virtual`, measures each row, and has its own scroll area, which fills the page through `PageLayout`'s new `fill` prop.
  - Below 60 rows it renders them all: as fast for a short list, and simpler.
  - Keiyoushi's 1469 are virtualized; Stages 4–5 reuse it.
  - The scroll position resets when the tab or the filters change, not when a row does.
- **Compat issues** (`compatIssues`, an in-memory lookup on the server) are part of the list fragment. A row with any shows "N API gaps", which opens a Reka `Popover` with each target, kind, origin, context and count.

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
### Stage 2 (2026-11-07)

- **Baseline before the stage:** `pnpm test`: 3 files, 19 tests; `pnpm lint`, `pnpm typecheck`, `pnpm format:check` clean; `cargo test`: 14; `cargo clippy --all-targets` clean. **After:** `pnpm test`: 10 files, 76 passed + 5 skipped (the live spec). That's client 15, ws 13, realtime 6, hooks 4, routes 15, mediaKind 3, ShellFooter 5 (HomeView's 4 ported, plus 1), backend store 9, BackendGate 6. lint, typecheck and format:check are clean. Nothing in `src-tauri` changed: `cargo build`, `cargo test` (14) and `cargo clippy --all-targets` still pass. Nothing in `server/` changed either, so it wasn't rebuilt (662 tests, smoke 24 from Stage 1 stand).
- **Live operation spec** (a server from `Start-MadoyomiServer` on a scratch data dir with the yuzono and Keiyoushi repos): 5/5. Every document in `api/operations/` validates against the real schema. A wrong token gets `UNAUTHORIZED`/401. The list queries run. `extensionStoreRefreshed` arrives over our WebSocket client (Node's `WebSocket`, which fails a handshake whose subprotocol isn't echoed, like a browser) after `fetchExtensions`.
- **Upstream's schema isn't valid by graphql-js's rules:** `DownloadChangedInput` and `LibraryUpdateStatusChangedInput` are input types without fields, which graphql-kotlin emits and graphql-js's `assertValidSchema` refuses. The spec builds the schema with `assumeValid: true`, which skips that check, and our documents are still validated. Nothing the client does depends on those two types.
- **Live in `pnpm tauri dev`** (`MADOYOMI_DATA_DIR` = that data dir), checked by screenshot. Sidebar clicks were driven with `SetCursorPos` + `mouse_event`, which reach WebView2, unlike in Stage 1, where no click was tried.
  - The shell: the sidebar, the switch, and the footer with `Madoyomi-Server v0.1.0 · http://127.0.0.1:62305` and a green "Live updates", so the WebSocket was open with the token.
  - Extensions → Repos: both repos with their counts (249 and 1469 extensions) and last refresh. This was the first authenticated request from the UI.
  - "Refresh all": the mutation ran, the two `extensionStoreRefreshed` events appeared under "Live events", and the refresh times updated through the invalidation.
  - Killing the backend's java (pid from `sidecar.log`): the overlay ("Starting the backend… attempt 2"), and the footer went "Offline". The backend was healthy again on port 61065 about 7 s later, the socket reconnected by itself ("Live updates", the new URL), and a second "Refresh all" brought two new events, with no window reload.
  - Browse in manga mode listed upstream's built-in Local source. Its icon loaded through `assetUrl` (`?token=`), so the binary routes take the query token too.
  - Closing the window: "backend stopped (exit code 0)". `token=` appears in none of `sidecar.log`, `madoyomi.log` or the dev output: Javalin doesn't log the WebSocket URL.
- **The Local source shows as "Untrusted".** Upstream's fake "Local Source" extension has no signature hash, so `extension.isTrusted` is false. Stage 4 should treat source id `0` as the built-in local source, not an untrusted one.
- **A repo's stored URL can differ from the one added.** Adding yuzono's `…/index.min.json` stored `https://github.com/yuzono/anime-repo/raw/repo/index.pb`: the repo manager prefers the protobuf index (Phase 1). Adding the same `index.min.json` again then counts as a new URL, though only one row exists afterwards. Stage 3's add form should show the stored URL and match existing repos by what the server returns.

### Stage 3 (2026-11-07)

- **Baseline before the stage:** the same as after Stage 2. `pnpm test` gave 10 files, 76 passed + 5 skipped; lint, typecheck and format:check were clean; `cargo test` 14 and `cargo clippy --all-targets` clean. `tools/smoke-api.ps1 -NoBuild` passed 24/24, rerun at the start on the existing jar.
- **After the stage:**
  - `pnpm test`: 14 files, 107 passed + 6 skipped (the live spec). The new ones: hooks 9 (was 4; type detection +5), TrustDialog 5, VirtualList 2, ExtensionsView 11, ReposView 8. Unchanged: client 15, ws 13, realtime 6, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6.
  - lint, typecheck and format:check are clean.
  - `cargo build`, `cargo test` (14) and `cargo clippy --all-targets` pass. Nothing in `src-tauri` changed.
  - Nothing in `server/` changed, so it wasn't rebuilt: Stage 1's 662 tests stand, and the smoke test passed 24/24 at the start.
- **Live operation spec** against a server from `Start-MadoyomiServer` (scratch data dir, both repos added): **6/6**.
  - All 19 documents validate, including the new mutations and the `RepoIndexFormat` enum.
  - Adding yuzono as MANGA fails with `REPO_TYPE_MISMATCH` and a `details.packages` list. That is what the type detection relies on.
  - `updateExtension`'s patch is passed as three Boolean variables, so the document doesn't depend on the name graphql-kotlin gives the patch's input type.
- **Live in `pnpm tauri dev` on a fresh `MADOYOMI_DATA_DIR`**, checked by screenshot (window driven as in Stage 2):
  - **Typing works:** click the field, then put the text on the clipboard and press Ctrl+V with `SendKeys`. The selects were driven with arrow keys and Enter.
  - **Both repos added in the UI.**
    - Extensions said "No manga repo yet … add a repo".
    - Keiyoushi's `index.pb` was added as "a manga repo (type detected), with 1469 extensions".
    - yuzono's `index.min.json` with the type forced to Manga showed "Couldn't add the repo / The repo's extensions aren't of the kind it was added as / REPO_TYPE_MISMATCH 249 of 249 extensions … are not manga extensions".
    - The same URL with "Detect the type" was added as "an anime repo (type detected), with 249 extensions. It is stored as `https://github.com/yuzono/anime-repo/raw/repo/index.pb`".
    - Both showed "Signing key trusted".
  - **The extension lists.**
    - Manga Available showed 1069: 1469, less the Local source and the 18+ ones. The icons loaded through `assetUrl`.
    - Thirty wheel scrolls reached the H's with no stall: the list is virtualized.
    - Anime showed 183.
  - **Installs with live steps:**
    - MangaPill showed "Converting…" with the bar, then moved to Installed (1). It was trusted by Keiyoushi's key, so it was loaded and listed under Browse.
    - AnimeGG did the same.
    - The live events list showed DOWNLOADING → VERIFYING → CONVERTING → INSTALLED for both.
  - **The trust prompt.** Repo keys are trusted on add, so the untrusted case came from the UI itself:
    - removing yuzono through its confirmation left AnimeGG "Untrusted", with Trust…;
    - TrustDialog showed AnimeGG (anime), the package, 14.2, "No added repo lists it any more" with the stored URL, the hash `cbec121a…c45905` and the warning;
    - Copy put the hash on the real clipboard;
    - "Trust and load it" cleared the badge, with no "Not loaded", so AnimeGG was loaded again;
    - yuzono was then re-added.
  - **A backend kill** (pid from `sidecar.log`): healthy again on a new port about 7 s later, with "Live updates" back.
    - Without a reload, the manga Installed list was there.
    - MangaPill uninstalled (the action finished before the next screenshot, about 0.3 s).
    - Reinstalled, it showed "Converting…" again: the events came over the reconnected socket.
  - **After closing the window:** "backend stopped (exit code 0)". `token=` appears in none of `sidecar.log`, `madoyomi.log` or the dev output.
  - **Fixed after the first live run, then rechecked live on the same data dir:**
    - links used the browser's visited purple; they now use the accent;
    - a long package name ran past the trust dialog's edge; it now wraps;
    - the search fields had spellcheck squiggles; spellcheck is off;
    - a search typed for anime stayed when switching to manga; it now starts over;
    - "Nothing matches" after an install now points to "1 under Installed";
    - the row badge said "All languages" where the filter says "Multi-language"; both say "Multi-language" now (`formatExtensionLang`).
- **Not checked live:**
  - An **update**: neither repo listed a newer version of an installed extension. It is covered by the component tests, and it is the same mutation as install with `update: true`.
  - A **compat-issue popover**: AnimeGG and MangaPill have no issues. It is covered by the component test.
  - Removing a repo **and** re-adding it moves AnimeGG's trust from the repo's key (STORE) to the user's (USER, from the trust prompt), as Phase 1 §9 describes. That was not inspected beyond the badges.
- **Reka in jsdom.** Tabs change on `mousedown` (a pointer press), not on `click`. Dialogs, alert dialogs and popovers portal to `<body>`, so the tests mount with `attachTo: document.body` (`src/test/mountView.ts`). `VirtualList`'s virtual path runs in jsdom once `clientHeight` (the viewport) and `offsetHeight` (the rows) are stubbed.
