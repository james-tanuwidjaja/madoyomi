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
- [x] Browse (source list) (Stage 4: the current kind's sources by language, pinned ones first (kept in `localStorage`), the Local source as built in, an untrusted or unloaded source not opened and pointing to Extensions; verified live for both kinds, §10)
- [x] Source (popular/latest/search + filters) (Stage 4: Reka tabs with Latest only when `supportsLatest` is true, a search box, the filter sheet with Apply and Reset, an endless `VirtualGrid` of `MediaCard`s, readable errors with a retry, the state per source for the session; verified live for both kinds, including a filtered search that changed the results, page 2 by scrolling and a backend kill, §10)
- [x] Details (anime + manga) with episode/chapter list (Stage 5: cover, title, author/artist, status, genres, source and description; refresh from the source by a button and by itself on the first open of an uninitialized entry; the source's error shown with the stored data kept; add to and remove from the library; a virtualized `EpisodeList`/`ChapterList` with a sort (source order, number, date, either way) and filters (watched/read as a Phase 6 placeholder, bookmarked, scanlator); a `Seasons` anime lists its seasons as cards (by the component tests only: no installed source has one); verified live for both kinds with 1190 episodes and 1214 chapters scrolled, and after a backend kill, §10)
- [x] Library (Stage 5: a `VirtualGrid` of `MediaCard`s of the current kind's library, sorted by date added or title, either way, per kind; an empty state that links to Browse; the browse cards' "In library" mark follows add/remove; verified live for both kinds and after a backend kill, §10)
- [x] Settings + source preferences (Stage 6: `/settings` with the theme (System/Dark/Light, persisted, applied before the first paint), the language (English only, said so), the backend's state, version, URL, data folder and log folder with Open logs, and the current kind's configurable sources; `/settings/source/:id` with the source's preferences, an unusable source pointing to Extensions; Settings links on Browse rows and the source page; verified live for both kinds and after a backend kill, §10)

### Renderers
- [x] `FilterRenderer` (all filter types + reset) (Stage 4: Header, Separator, Text, CheckBox, TriState, Select, Sort, Group; only the changes from the defaults are sent, group members through `groupChange`; round-trip and the server test's change list checked on shared fixtures, and live against KickAssAnime and MangaPill, §10)
- [x] `PreferenceRenderer` (all preference types) (Stage 6: List (Reka Select, `%s` summaries), EditText (a dialog with its title and message, saved by Enter or Save), Switch, CheckBox, MultiSelect (checkboxes in a dialog, applied on Save), each with its title and summary, disabled and hidden as the extension says; only the changed preference is sent, by its position; optimistic updates with a rollback on an error and the server's value on a refusal; component tests for every type; live: a list, a switch, a multi-select and an edit text on KickAssAnime and MangaDex. A checkbox only by the tests: no installed source has one, §10)

### Quality
- [x] Virtualized grids/lists (Stage 3: `VirtualList` for the extension list, 1469 Keiyoushi rows; Stage 4: `VirtualGrid` for the source results, verified live over many pages; Stage 5: the Library grid (`VirtualGrid`) and the episode/chapter lists (`VirtualList`, tested with 1500 rows; live with 1190 episodes and 1214 chapters scrolled end to end), §10)
- [x] Covers via backend image proxy (Stage 4: the source grid's covers load through the backend's thumbnail routes, `/api/v1/anime/{id}/thumbnail` and upstream's manga one, which apply the source's headers, so no new proxy was needed; verified live for both kinds and after a backend restart. Stage 5: the details and Library covers use the same routes through `useAssetUrl`, verified live for both kinds and after a backend kill, §10)
- [ ] Component + client tests
- [ ] `pnpm lint`, `pnpm typecheck`, `pnpm test` pass

## 6. Exit criteria

- [ ] Starting from a fresh data dir, a user can add both repos, install and trust one anime and one manga extension, browse/search with filters, open details, see episodes/chapters and add both to the library, all in the UI.
- [x] Source preferences can be changed in the UI and take effect. (Stage 6, §10: KickAssAnime's "Use English titles" turned Popular's titles into English ones and its mirror changed the source's address; MangaDex's "Filter original languages" set to Korean left only Korean titles in Popular. All restored afterwards, the MangaDex one after a backend kill without a reload. Rechecked in the exit run.)
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
   - *Done* (2026-11-07):
     - unit tests of the filter state on fixtures shared with the server's `AnimeBrowseApiTest`;
     - component tests for `FilterRenderer`/`FilterSheet`, `VirtualGrid`, Browse and the source page;
     - the live spec's new filtered search (7/7);
     - live in `pnpm tauri dev` for both kinds (§10): pins, Popular, Search, filtered searches that changed the results, pages loaded by scrolling, and the source page working on after a backend kill.
   - Nothing in `server/` changed.
5. **Details and Library** (steps 15–16, 20): details for anime and manga, with a virtualized `EpisodeList`/`ChapterList` (1000+ entries), sort and filter; add to library; the Library grid with a sort and an empty state.
   - *Done* (2026-11-07):
     - one server addition, `updateAnime`/`updateAnimes` (the library flag for anime), with `AnimeLibraryApiTest`;
     - unit tests of the list's sort and filters, component tests of the list (1500 rows), both details views and the Library, the refresh calls over a mocked client, and a hook test;
     - the live spec's new refresh and library round trip (8/8);
     - live in `pnpm tauri dev` for both kinds (§10): an automatic first refresh, 1190 episodes and 1214 chapters scrolled, sort and scanlator filter, add and remove, the Library's sorts, the browse card's mark, and all of it after a backend kill.
6. **Settings and `PreferenceRenderer`** (steps 17, 19): general settings (theme System/Dark/Light, language), backend settings (data dir, log folder), and source preferences saved with optimistic updates. Verified by renderer unit tests and a live preference change that takes effect.
   - *Done* (2026-11-07):
     - unit tests of the preference values and changes, component tests of `PreferenceRenderer` for every type, the optimistic update and its rollback over mocked operations, the calls over a mocked client, and both views;
     - the live spec's new preference round trip for each kind (9/9);
     - live in `pnpm tauri dev` (§10): the three themes, Open logs, the Settings links, a preference of each kind taking effect on the source page, and all of it after a backend kill.
   - Nothing in `server/` changed: the manga quirks are worked around in the client (§9).
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

### Stage 4 (2026-11-07)

Decided in the stage (defaults, not asked):
- **No server change.** The source fragment gained `baseUrl` and `extension.isLoaded`, which both source types already had. Covers use the existing thumbnail routes. `server/` and `server/UPSTREAM.md` are untouched.
- **Pinned sources live in `localStorage`** (`madoyomi.pinnedSources`, `{anime: [ids], manga: [ids]}`, in pin order; `stores/pinnedSources.ts`), not on the server:
  - a pin is a per-device UI preference, like Mihon's `pinnedSources` app preference;
  - upstream's per-source metadata (`SourceMetaTable`, `setSourceMeta`) exists only for manga sources, so a server setting would have needed a server change for anime;
  - a pin of an uninstalled source stays and shows again if the source comes back.

  Phase 6's backup can carry the pins if that is wanted (it would read them from the UI).
- **Browse's order:** Pinned (in pin order), then the languages as before (multi-language first, "other" last, each by name; a pinned source shows only under Pinned, with its language), then "Built in" for the Local source.
- **Source access** (`utils/sources.ts`): id `0` is `builtIn` (upstream's Local source: its fake extension has no signature, so `isTrusted` is false, but it is always usable). Otherwise a source is `untrusted` (`extension.isTrusted` false) or `notLoaded` (trusted but `isLoaded` false), or `ok`.
  - Browse doesn't link a source that can't be used. It shows "Untrusted" or "Not loaded" and **"Open in Extensions"**, which opens the Installed tab searched for that package (`composables/useOpenInExtensions.ts`), where the Trust button or the install error is.
  - The source page, opened by URL, shows the same reason and browses nothing; it doesn't even ask for the filters, which would fail with `UNTRUSTED`.
- **Filters apply to a search, as on the server and in Mihon.**
  - `fetchSource*` uses the filters only for `SEARCH`, so Apply switches to the Search tab, with the current query or none.
  - A filter-only search has `query: ""`. MangaPill and AnimeGG use their filters only without a query (their filter lists say so in a header: "Ignored if using text search!"), so a filter-only search is the usual one.
  - The Search tab with neither a query nor a filter asks for one and fetches nothing.
- **Only the changes from the defaults are sent** (`utils/filters.ts`). Upstream's `updateFilterList` applies the changes to a fresh `getFilterList()` per request (core `graphql/types/SourceType.kt`).
  - A filter at its default sends nothing, and the empty list is the source's defaults.
  - A group member is `{position: group, groupChange: {position: member, …}}`, one change per member.
  - Headers and separators never send anything.
  - `filterStateFromChanges` is the inverse; the tests check the round trip, and that the server test's own change list comes out for the same values.
- **The filter state is a plain array parallel to the filter list** (a group's entry is its members' values). The query key carries the changes, so a change of filters starts over at page 1.
- **Per source, for the session** (`stores/sourceBrowse.ts`, keyed `kind:sourceId`): the tab, the submitted search and the applied filter values.
  - Nothing is persisted.
  - The filters are stored with `filterSignature(filters)` (each filter's kind, name and options). After an extension update changes them, the old values are dropped rather than sent to positions that now mean something else.
  - Stored filters wait for the filter list (cached for good after the first load) rather than searching without them.
- **The filter sheet** (`FilterSheet.vue`) is a Reka `Dialog` drawn as a right-hand side sheet. It edits a copy of the applied values, made each time it opens:
  - **Apply** applies the copy;
  - **Reset** puts the defaults back into the copy (Apply then applies them);
  - Cancel, Escape or a click outside throw the copy away.

  The page also has **"Clear filters"** while filters are applied, and the Filters button shows how many changes are applied.
- **The controls** (`FilterField.vue`):
  - Select: Reka `Select`.
  - Sort: a Reka `Select` for the field plus an Ascending/Descending button. "None" is offered only when the source's default is none. A new field starts descending, as in Mihon; the same field keeps its direction.
  - TriState: a Reka `Checkbox` we control. A click cycles ignore → include (✓) → exclude (its indeterminate state, drawn as ✕ in the danger colour) → ignore, and its label names the state.
  - CheckBox: a Reka `Checkbox`. Text: a text field.
  - Group: a Reka `Collapsible`, closed at first, its header showing how many members are changed.
  - The select list portals above the sheet (z-index 60).
- **`VirtualGrid.vue`**: `VirtualList`'s pattern for cards.
  - The columns come from the scroll area's width (a `ResizeObserver`): as many 150 px columns as fit, with 12 px gaps, stretched to fill.
  - Above 60 items, rows of cards are virtualized and measured.
  - It emits `end-reached` within 600 px of the end, on a scroll and after the items change (so a page that doesn't fill the view asks for the next), and never while it has no height.
  - The page loads the next page on `end-reached` only while `hasNextPage` and nothing is fetching or failed, and there is a "Load more" button as well.
- **Results are deduplicated by entry id across pages.** Sources repeat entries on the next page, and a repeated key would break the grid.
- **Errors.**
  - A failed first page shows `ErrorNotice` ("Couldn't load <source>", the readable line, the code) with Try again.
  - A failed next page keeps the loaded pages and shows the notice in the footer, retrying that page; scrolling doesn't retry it by itself.
  - The existing realtime logic refetches failed queries when the backend is back.
- **`MediaCard.vue`**: the cover from `useAssetUrl(thumbnailUrl)` (the backend's thumbnail route with `?token=`, which follows a restart), lazy-loaded, 2:3. When there is no URL or the image fails, it shows the title's first letter. The title is clamped to two lines, with an "In library" badge, linking to `/anime/:id` or `/manga/:id`.

### Stage 5 (2026-11-07)

Decided in the stage (defaults, not asked):
- **One server addition: `updateAnime`/`updateAnimes`** (`madoyomi.api.graphql.AnimeMutation`, in the shape of upstream's `updateManga`/`updateMangas`; server/UPSTREAM.md "Library flag (Phase 3)"). Nothing set `ANIME.in_library` before.
  - Adding sets `added_at` to now. Removing keeps it, as upstream keeps `inLibraryAt`.
  - An unknown id is `NOT_FOUND`, and nothing is written.
  - Upstream's queueing of uninitialized entries for a library update and its thumbnail move are left to Phase 6. The UI refreshes an uninitialized entry when it is opened.
- **The anime refresh is one call, the manga refresh two.**
  - Anime: `fetchAnimeAndEpisodes` through `api.gqlResult`. A source failure comes back as a partial result: the stored data and the error.
  - Manga: upstream's `fetchManga`, then `fetchChapters` (`refreshManga` in `api/operations/entries.ts`). Upstream's `fetchMangaAndChapters` would do both in one call, but it reports its partial error through `toGraphQLError()`, without `extensions.code`, so our instrumentation makes it `INTERNAL`. The two calls are classified like any resolver error, so a site's failure arrives as `SOURCE_FAILED`.
  - A failed details fetch stops there. The page then gets what is stored (read again) with the error. A transient error (the backend down) or `NOT_FOUND` throws instead.
- **When a details page refreshes** (`composables/useDetailsPage.ts`):
  - by itself only when the entry was never fetched (`initialized` false), and only once per entry per app session (`claimAutoRefresh`), so a failing site isn't asked on every visit;
  - otherwise by the Refresh button.
  - The result, which is the stored data even when the source failed, is written into the entry, episodes/chapters and seasons queries. Nothing is fetched twice.
  - The source's error shows as `ErrorNotice` ("Couldn't refresh from <source>", with Try again) above the stored data.
- **A source that can't be used isn't asked.** A details page finds the entry's source in the kind's source list (`sourceAccess`).
  - When the source is missing, untrusted or not loaded, the page doesn't refresh, the Refresh button is disabled, and a notice says why. For an untrusted or unloaded source there is "Open in Extensions".
  - Without this, upstream's manga refresh of a missing source fails with a null pointer ("Missing source", `INTERNAL`).
  - The source's name links to its page only while the source can be browsed.
- **The episode/chapter list** (`EntryItemList.vue`, behind `EpisodeList`/`ChapterList`; logic in `utils/entryItems.ts`):
  - the sort: the source's order, the number or the upload date, either way, with ties broken by the source's order. It is kept per kind in `localStorage` (`madoyomi.itemSort`). The default is the source's order, newest first, as in Mihon;
  - the filters: watched/read (a placeholder until Phase 6 records progress, and the list says so), bookmarked only, and a scanlator, shown only when the entry has two or more. They are per entry, for the session (`stores/itemList.ts`): a scanlator belongs to one entry, and a filter left on would hide the next entry's list;
  - native `<select>`s, as decided for plain lists of strings in Stage 3;
  - a `VirtualList` (rows of about 56 px); the scroll goes back to the top when the sort or the filters change;
  - rows don't act yet. A line above the list says that playing comes in Phase 4 and reading in Phase 5.
- **A `Seasons` anime** shows its seasons (`anime(id).seasons`) as a `VirtualGrid` of `MediaCard`s, each linking to its own details. A season names the entry it belongs to ("Season 1 of …", linking to it).
- **The library change patches what is cached** (`useSetInLibrary`):
  - the entry's query gets the stored entry;
  - every cached browse page of that kind gets the new mark on that card (`setQueriesData`, no refetch, so the source isn't browsed again);
  - the library list is refetched, and so is a season's parent's season list.
- **The Library** (`LibraryView.vue`): `animes`/`mangas` with `condition: {inLibrary: true}`, sorted in the UI.
  - The sort is by date added or title, either way, per kind, kept in `localStorage` (`madoyomi.librarySort`).
  - A new sort key starts in its natural direction: newest first for dates, A to Z for titles. The default is date added, newest first.
  - The cards hide the "In library" badge (`MediaCard`'s `hideLibraryBadge`). The count is in the subtitle.
  - An empty library says how to add entries and links to Browse.
- **Details pages have their own heading** (`EntryHeader.vue`).
  - `PageLayout`'s title is optional now: a page without a title or actions has no title row.
  - While the entry loads, or without a backend, the page shows "Anime"/"Manga" as its title, so the route smoke test still finds a heading.
- **Link-styled buttons** (`RouterLink class="btn"`) are no longer underlined (`.btn` gets `text-decoration: none`). The Library's empty state was the first such link that was underlined.

### Stage 6 (2026-11-07)

Decided by the user:
- **The manga preference quirks are worked around in the client, with no server change.** Upstream's `Source.setSourcePreference` uses the screen its last read cached (`preferenceScreenMap[sourceId]!!`), so a change sent before any read since the server started is a null pointer (`INTERNAL`); a backend restart empties that cache. So the UI's manga change (`updateSourcePreference` in `api/operations/preferences.ts`) reads the preferences first, then sends the change: two calls, like Stage 5's manga refresh. Upstream also saves the value first and then calls the extension's listener, ignoring its answer, so a manga extension can't refuse a value. That stays as upstream has it, recorded in §10. A server fix (build the screen when none is cached; listener first, as on the anime side) was the alternative, at the cost of an upstream edit and a full build.

Decided in the stage (defaults, not asked):
- **No server change.** The preference API (Phase 2 Stage 4) had everything: both kinds' queries and mutations, `isConfigurable` and the union's types. `server/` and `server/UPSTREAM.md` are untouched.
- **The documents** (`api/operations/preferences.ts`): `PREFERENCE_FIELDS` with every member's fields aliased by kind (`switchCurrentValue`, `listDefault`, …), the preferences of a source of each kind, and both mutations with the whole input (`UpdateAnimeSourcePreferenceInput`, `UpdateSourcePreferenceInput`). They answer `{preferences, source}`. The live spec validates them (31 documents).
- **Optimistic updates and their rollback** (`composables/usePreferences.ts`, step 19):
  - a change writes the new value into the cached list at once, so the control moves on the click;
  - changes run one at a time and in order, in one vue-query mutation scope (`sourcePreferences`), so the server sees them as they were made. Each one still shows at once, because `onMutate` runs before the scope's wait;
  - when the last pending change of the source answers, the server's list replaces the cached one. An earlier answer isn't written while a later change waits, so the later value doesn't flicker back;
  - **a failure** (any error, `BAD_REQUEST` from the server or the backend down) puts the preference back as it was before that change, and then reads the list again when no other change of the source is pending. The row shows the error (`ErrorNotice` with the readable line, no retry: the control is the retry);
  - **a refusal** isn't an error on the server: the anime side runs the extension's listener first and saves only if it agrees, then answers with what it stored. So the UI compares the answer's value at that position with the one sent (`changeTaken`; a multi-select as a set). When they differ, the cached list already holds the kept value, and the row says "<extension> didn't accept that value and kept <value>";
  - the status (saving, error, refused) is kept per position for the page. An older change's answer doesn't overwrite a newer change's status.
- **What a change invalidates.** The server drops the cached source, and its next use is a new instance that reads the new settings. So the UI:
  - removes the source's cached browse pages, so the source page browses again (the "takes effect" part);
  - invalidates the source's filters, which an extension may build from its settings;
  - refetches the kind's source list once after the source's next browse page (`markSourceChanged` in `composables/useSources.ts`). The server stores the new instance's `baseUrl` (a mirror setting changes it) only when that instance loads, at the next browse. The mutation's own `source` is the old row, so patching the list with it would have kept the old address (found live, §10).
- **The preferences query has no stale time**: one change can change other preferences (a summary, `enabled`, `visible`), so the page reads the list each time it opens.
- **`PreferenceRenderer`** draws each visible preference with its original position (hidden ones count, as on the server), and `PreferenceField` draws one row: the title, the summary, the control.
  - Switch: Reka `Switch`. CheckBox: Reka `Checkbox`. List: Reka `Select`, on the entries' indexes (an entry value can be `""`, which a select item can't be). These apply at once.
  - EditText and MultiSelect open a Reka `Dialog`, as Android's preference dialogs do, with `dialogTitle` (or the title) and `dialogMessage`. The edit text saves on Enter or Save; the multi-select applies its checkboxes on Save and sends the whole set, in the entries' order. Cancel, Escape or a click outside change nothing. A value equal to the stored one sends nothing.
  - A list's `%s` summary is its current entry (`%%` a percent sign), as Android's `ListPreference` formats it. An edit text without a summary shows its value as the summary; one with a summary shows its value on a line below (found live: otherwise the row doesn't say what is stored).
  - A disabled preference is greyed out and its control disabled; the server would leave it unchanged anyway.
- **The theme** (`stores/theme.ts`): System, Dark or Light, kept in `localStorage` (`madoyomi.theme`; System removes the key). It sets `<html data-theme>`, or removes it for System, which tokens.css reads (Stage 1). A small inline script in `index.html` sets the attribute before the first paint, so a chosen theme doesn't flash the other one while the app loads (the CSP is off in `tauri.conf.json`). The control is a Reka `ToggleGroup` drawn like the sidebar's anime/manga switch; a click on the chosen option keeps it.
- **Language: English only**, and the page says so. There's no i18n layer yet.
- **The backend section** shows the state, the server's version and URL, and the data and log folders from the status snapshot, with Open logs (`open_backend_logs`). A backend not started by the app (`pnpm dev` with `VITE_MADOYOMI_URL`) has no folders to show, and the page says so.
- **Source settings are reached three ways:** the Settings page lists the current kind's sources with `isConfigurable` true (by name, then language); Browse rows of such a source have a ⚙ link next to the pin; the source page has a Settings button. A source that can't be used (`isConfigurable` null) isn't listed, and Settings counts those and points to Extensions.
- **`/settings/source/:id`** uses the current kind, as the source page does. It handles a missing source, an untrusted or unloaded one (Open in Extensions; no preference request, which would fail with `UNTRUSTED`), a source without settings, and a failed read (`ErrorNotice` with Try again). While it loads, the title is "Source settings", so the route smoke test still finds a heading.

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

### Stage 4 (2026-11-07)

- **Baseline before the stage** (the same as after Stage 3):
  - `pnpm test`: 14 files, 107 passed + 6 skipped;
  - lint, typecheck and format:check clean;
  - `cargo test` 14 and `cargo clippy --all-targets` clean.
- **After the stage:**
  - `pnpm test`: 19 files, 148 passed + 7 skipped (the live spec). The new ones: filters 10, FilterRenderer/FilterSheet 8, VirtualGrid 5, BrowseView 6, SourceView 12. Unchanged: client 15, ws 13, realtime 6, hooks 9, routes 15, mediaKind 3, ShellFooter 5, backend store 9, BackendGate 6, TrustDialog 5, VirtualList 2, ExtensionsView 11, ReposView 8.
  - lint, typecheck and format:check are clean.
  - `cargo build`, `cargo test` (14) and `cargo clippy --all-targets` pass. Nothing in `src-tauri` changed.
  - Nothing in `server/` changed, so it wasn't rebuilt: Stage 1's 662 tests and Stage 3's smoke run (24/24) stand.
- **Live operation spec: 7/7.** The server came from `Start-MadoyomiServer` on a scratch data dir with both repos, AnimeGG, MangaPill and KickAssAnime.
  - The source fragment's new fields validate.
  - The new filtered search: for each kind it changes one filter of an installed source through `filterChanges` and searches with an empty query. The output:
    - AnimeGG `[{position: 1, selectState: 1}]` → `SOURCE_FAILED HTTP error 404`, so the next source was tried;
    - KickAssAnime `[{position: 1, selectState: 1}]` → 30 entries;
    - MangaPill `[{position: 2, selectState: 1}]` → 50 entries (0 unfiltered).
- **AnimeGG's own filter is broken on its site.** Its only filter, a Genre select labelled in Spanish ("<Seleccionar>", "Películas", "Series", under the header "La busqueda por texto ignora el filtro"), gives an HTTP 404 for every value. The extension builds a URL the site doesn't have; Madoyomi sends the change as it should (the same change works on other sources). This is an extension problem, not ours. The live spec tries the next source of the kind when a site fails.
- **Sources use their filters only without a query.** A search with an empty query and no filters returns nothing on AnimeGG and MangaPill (0 entries each), while KickAssAnime returns its list (30). MangaPill and AnimeGG say in a header that a text search ignores the filters. A filter-only search is therefore the usual filtered search, which is why the Search tab accepts filters without a query.
- **Live in `pnpm tauri dev`** (`MADOYOMI_DATA_DIR` = that data dir), driven by clicks, wheel and pasted text, checked by screenshot:
  - **Browse, anime:** AnimeGG and KickAssAnime under EN. Pinning KickAssAnime moved it to "Pinned", with "KickAssAnime · EN" and a filled star.
  - **Browse, manga:** MangaPill under EN and the Local source under "Built in", with a "Built in" badge and no "Untrusted". No pinned group: pins are per kind.
  - **KickAssAnime:**
    - Popular, Latest and Search tabs (`supportsLatest` true), with "EN · https://kaa.lt";
    - Popular as six columns of covers loaded through the anime thumbnail route;
    - wheel scrolling kept loading pages, and the scrollbar thumb jumped back as each one arrived;
    - a search for "naruto" gave the Naruto titles.
  - **KickAssAnime filters:** the sheet showed its Genre group, Year/Status/Type selects, a separator and a header.
    - Type → Movie, Apply: only films (Akira, the 5-toubun movie, Bakemono no Ko), with "Filters 1" and "Clear filters".
    - Reopened, the sheet said "1 filter changed".
    - Genre group → Comedy, Apply: comedy films (City Hunter and Crayon Shin-chan movies), "Filters 2".
  - **MangaPill:**
    - Popular: 10 entries, "10 entries, the end of the list", the covers through upstream's manga thumbnail route;
    - a search for "one" loaded page 2 when scrolled, with "Loading more…" in the footer.
  - **MangaPill filters:**
    - Genres group: Action included (✓) and Comedy excluded (✕), "Genres 2", "3 filters changed".
    - Reset brought back "The source's defaults".
    - Type → Novel, Apply: only light novels (One Piece Novel A, the Kimetsu no Yaiba novels), where the unfiltered empty search finds nothing.
    - Type → Manhwa (alone, with Action and without Comedy, or with "solo") gave "Nothing matches". Replaying the same change against the server gave 0 entries for Manhwa and 50 for Manga, so that is MangaPill's site, not the UI.
  - **A backend kill** (pid from `sidecar.log`): healthy again on a new port about 7 s later.
    - Without a reload, on the same MangaPill page, a search for "solo" ran against the new port.
    - "Clear filters" then gave 8 entries.
    - One cover that failed to load showed its title's initial.
  - **After closing the window:** "backend stopped (exit code 0)". `token=` appears in none of `sidecar.log`, `madoyomi.log` or the dev output.
- **Fixed after the first live run:** a closed filter group was 12 px taller than its header. Reka's `CollapsibleContent` keeps its element with `hidden=""` (only the children unmount), and our `display: grid` overrode the `hidden`. `.group-content[hidden]` is now `display: none`, and this was rechecked live.
- **Not checked live:**
  - an untrusted or unloaded source: none was installed. It is covered by the component tests of both views;
  - a source with `supportsLatest` false: neither source lacks Latest. It is covered by the component tests;
  - a next-page error: no site failed mid-scroll. It is covered by the component test.
- **Reka in jsdom.** `Select` opens on Enter on its trigger, and an option is chosen with Enter on it (`keydown`, bubbling), once `scrollIntoView`, `hasPointerCapture` and `releasePointerCapture` are stubbed. `Collapsible` opens on click. The tri-state's `Checkbox` reacts to `click`.

### Stage 5 (2026-11-07)

- **Baseline before the stage** (the same as after Stage 4):
  - `pnpm test`: 19 files, 148 passed + 7 skipped;
  - lint, typecheck and format:check clean;
  - `cargo test` 14 and `cargo clippy --all-targets` clean;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24 at the start, on the Stage 4 jar.
- **After the stage:**
  - `pnpm test`: 25 files, 186 passed + 8 skipped (the live spec). The new ones: entryItems 5, EntryItemList 5, AnimeDetailsView 9, MangaDetailsView 6, entries (the refresh and library calls over a mocked client) 7, LibraryView 5, hooks 10 (was 9: `useSetInLibrary`). The other files are unchanged; routes 15 still passes.
  - lint, typecheck and format:check are clean.
  - `cargo build`, `cargo test` (14) and `cargo clippy --all-targets` pass. Nothing in `src-tauri` changed.
- **Server:**
  - `.\gradlew.bat build` is green with **667 tests, 0 failures**: core 604 (9 skipped in this run's results), AndroidCompat 7, ext-host 8, api 48 (was 43: `AnimeLibraryApiTest` 5);
  - ktlint is clean;
  - the jar was rebuilt by the same build, and `tools/smoke-api.ps1 -NoBuild` passed **24/24** on it.
- **Live operation spec: 8/8.** The server came from `Start-MadoyomiServer` on a fresh scratch data dir with both repos, AnimeGG, MangaPill and KickAssAnime.
  - All 27 documents validate, including the new ones.
  - The new refresh-and-library test:
    - AnimeGG's first Popular entry (One Piece) came back with 1167 episodes, and MangaPill's with 405 chapters;
    - both went into their library list with an `inLibraryAt` and out again;
    - the filtered search gave the same results as in Stage 4.
- **Live in `pnpm tauri dev`** (`MADOYOMI_DATA_DIR` = that data dir), driven by clicks, wheel, keys and pasted text, checked by screenshot:
  - **Library, empty:** "Your manga library is empty", the hint, and "Browse manga sources".
  - **Anime details, AnimeGG → Detective Conan** (never fetched):
    - the page opened with the stored cover and title, "Refreshing…" and "Fetching the episodes from AnimeGG…";
    - after the refresh it showed "Ongoing", Adventure, the description clamped with More, and **1,190 episodes**, newest first;
    - 30 wheel scrolls of 2400 reached episode 233 without a stall;
    - "↑ Ascending" put Episode 1 first and scrolled back to the top.
  - **Add to library:** the button turned into "Remove from library" with an "In library" badge. Back on AnimeGG's Popular, the Conan card had "In library" with no new browse.
  - **Bleach** (366 episodes) was added too. Its scanlator filter (AnimeGG puts the versions there: "SUBBED, SD, SD, DUBBED", "DUBBED", …) set to DUBBED gave "366 episodes · 9 shown", and Clear filters undid it.
  - **The anime Library:** "2 saved anime". Date added, newest first, gave Bleach, Conan; "Oldest first" gave Conan, Bleach; Title gave A to Z.
  - **Manga, MangaPill → search "one piece" → One Piece** (never fetched):
    - refreshed by itself (details, then chapters), with "Ongoing", six genres and **1,214 chapters**;
    - 50 wheel scrolls reached Chapter 2, the end of the list;
    - More showed the whole description, and Less clamped it again;
    - added, it was in the manga Library ("1 saved manga"), which kept its own sort (Date added) while anime stayed on Title.
  - **A backend kill** (pid from `sidecar.log`): healthy again on a new port 7 s later. Without a reload:
    - the One Piece card opened its details, cover included;
    - Refresh ran ("Refreshing…") against the new port;
    - "Remove from library" worked, and the manga Library showed the empty state;
    - the anime Library still listed both, refetched from the new port.
  - **After closing the window:** "backend stopped (exit code 0)". `token=` appears in none of `sidecar.log`, `madoyomi.log` or the dev output.
- **Upload dates are the first fetch's date for these sources.** Every episode and chapter showed the day of the first fetch. AnimeGG and MangaPill give no dates, and the reconcile then gives an undated new row the newest known date, or the fetch time when none has one (upstream's chapter reconcile, which Phase 2's `AnimeDetails` ported). Sorting by date then follows the order of the fetches. That's how the server stores it, not the UI.
- **Fixed after the first live run:** the Library's empty-state link, a `RouterLink` styled as `.btn`, was underlined. `.btn` now has `text-decoration: none`, rechecked live.
- **Not checked live:**
  - a `Seasons` anime: none of the installed sources has one. It is covered by the component tests (the season cards and their links, and a season's link to its parent);
  - a refresh error on screen: no site failed during the live run. It is covered by the component tests (`SOURCE_FAILED` kept with the data, `NETWORK` after a kill) and the `refreshManga` tests;
  - an untrusted or missing source on a details page: none was installed that way. It is covered by the component tests;
  - a Library above 60 entries (the grid's virtual path): only 2 were added. `VirtualGrid`'s virtual path was verified live in Stage 4, and it is tested.
- **Found in the tests:**
  - ESLint's `vue/no-side-effects-in-computed-properties` reads any method called `sort` as `Array.prototype.sort`, so the store method is `sortOf`.
  - The route smoke test needs a heading on `/anime/:id` without a backend, hence the "Anime"/"Manga" title while the entry loads.

### Stage 6 (2026-11-07)

- **Baseline before the stage** (the same as after Stage 5):
  - `pnpm test`: 25 files, 186 passed + 8 skipped;
  - lint, typecheck and format:check clean;
  - `cargo test` 14 and `cargo clippy --all-targets` clean;
  - `tools/smoke-api.ps1 -NoBuild`: 24/24 at the start, on the Stage 5 jar.
- **After the stage:**
  - `pnpm test`: 30 files, 220 passed + 9 skipped (the live spec). The new ones: preferences 5, PreferenceRenderer 6, preferenceCalls 4, usePreferences 5, SettingsView 7, SourceSettingsView 5; BrowseView 7 (was 6) and SourceView 13 (was 12) for the Settings links. The other files are unchanged; routes 15 still passes.
  - lint, typecheck and format:check are clean.
  - `cargo build`, `cargo test` (14) and `cargo clippy --all-targets` pass. Nothing in `src-tauri` changed.
  - Nothing in `server/` changed, so it wasn't rebuilt: Stage 5's 667 tests and this stage's smoke run (24/24) stand.
- **Live operation spec: 9/9.** The server came from `Start-MadoyomiServer` on a fresh scratch data dir with both repos, AnimeGG, MangaPill, KickAssAnime and MangaDex (`eu.kanade.tachiyomi.extension.all.mangadex`).
  - All 31 documents validate, the four new ones included.
  - The new preference round trip, for each kind, on the first configurable source with a switch, checkbox or list:
    - AnimeGG's `preferred_language`, `[SUBBED]` → `[DUBBED]`, taken and read back, then restored;
    - MangaDex's `thumbnailQuality`, `""` → `.512.jpg`, the same.
  - The Stage 5 library test took the first usable manga source, which was now a MangaDex language source with an empty Popular (14 of them in a row: ur, da, lt, …). It now tries the next source until one has entries, as the filtered search already did.
- **Which installed sources have settings:**
  - AnimeGG: 3 lists (language, server, quality);
  - KickAssAnime: 7 (the mirror `kaa.lt`/`kaa.to`, "Use English titles", the audio languages, quality, server, and a multi-select of excluded hosts);
  - MangaDex (each language source): 12 (cover quality, 7 switches, two multi-selects (content rating, original languages) and two edit texts (blocked groups, blocked uploaders));
  - MangaPill and the Local source: none.
  - No installed source has a `CheckBoxPreference`, so the checkbox is checked by the component tests only.
- **Live in `pnpm tauri dev`** (`MADOYOMI_DATA_DIR` = that data dir), driven by clicks, wheel, keys and pasted text, checked by screenshot:
  - **Settings:** the theme on System (dark, as Windows is), the language, "Running", `Madoyomi-Server v0.1.0 · http://127.0.0.1:57364`, the data and log folders, and the anime sources AnimeGG and KickAssAnime.
  - **The theme:** Light and Dark switched the whole window at once, and System went back to Windows' dark. Light was still on after a window reload.
  - **Open logs** opened an Explorer window on `<data dir>\logs`.
  - **KickAssAnime, a switch:**
    - Popular first showed the Romaji titles ("Boku no Hero Academia 7th Season", "Mahoutsukai no Yome Season 2");
    - the source page's Settings button opened its 7 preferences, with the `%s` summaries showing the entries ("kaa.lt", "Japanese", "1080p");
    - "Use English titles" on moved at once. Back on the source page, Popular browsed again and showed "My Hero Academia Season 7" and "The Ancient Magus' Bride Season 2": **it took effect**.
  - **KickAssAnime, a list:** the mirror kaa.lt → kaa.to was saved, with the summary following.
  - **MangaDex, a multi-select:**
    - Browse's EN group had MangaDex with a ⚙, and MangaPill (no settings) without one;
    - "Filter original languages" opened its dialog (Japanese, Chinese, Korean). Korean, then Save, showed "Korean";
    - Popular then had only Korean titles ("Copying Skills With Affinity!", "First Love Trap", …, 4 entries): **it took effect for manga too**.
  - **MangaDex, an edit text:** "Block groups by UUID" opened its dialog. A pasted UUID saved with Enter and showed under the summary. Cleared, then Enter, it was empty again.
  - **A backend kill** (pid from `sidecar.log`): healthy again on a new port 10 s later. Without a reload:
    - MangaDex's settings showed Korean still selected (the extension's own SharedPreferences). Unticked and saved, Popular had its full list again;
    - KickAssAnime's "Use English titles" off brought back the Romaji titles.

    Every preference changed in the run was back at its first value at the end.
  - **After closing the window:** "backend stopped (exit code 0)". `token=` appears in none of `sidecar.log`, `madoyomi.log` or the dev output.
- **Fixed after the first live run:**
  - **The source's address stayed old after a mirror change.** The mutation answers with the source's stored row, and the server only stores the new instance's `baseUrl` when that instance loads, at the next browse. So the page kept "kaa.lt" while it browsed kaa.to, until a window reload. Now the source list is read again once after that browse (§9). Rechecked live: kaa.to → kaa.lt showed "https://kaa.lt" on the source page without a reload.
  - **An edit text with a summary didn't show its value.** It now shows it on a line below the summary.
  - **"Open logs" wrapped onto two lines** beside a long log path. It no longer wraps.
- **Upstream's manga preference change** (core `manga/impl/Source.kt`, unchanged):
  - it needs the screen of an earlier read in the same server process (a null pointer otherwise). The UI's read-first covers it, and the change after the backend kill above went through;
  - it saves the value before calling the extension's listener and ignores its answer, so a manga extension can't refuse a value. The anime side follows Android's order (Phase 2 §9). A refusal is therefore only possible on anime, and none of the values tried live was refused. The refusal line is covered by the component and hook tests.
- **Not checked live:**
  - a refused value: none of the extensions refused one. It is covered by the tests (hook, view and renderer);
  - a failed save: no save failed. It is covered by the hook test (rollback and reread) and the renderer test (the error line);
  - a checkbox preference: none of the installed sources has one;
  - the very first paint in a chosen theme: the screenshots can't catch the frame before the app's own styles. The inline script is in `index.html`, and the theme was kept across a reload.
