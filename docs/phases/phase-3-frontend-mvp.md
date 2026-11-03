# Phase 3 — Frontend MVP

> Back to [PLAN.md](../PLAN.md) · Prev: [Phase 2](phase-2-anime-sources.md) · Next: [Phase 4](phase-4-playback.md)

## 1. Goal

Build the Vue 3 UI so a user can do everything from Phases 1–2 without curl: manage repos and extensions (including the trust prompt), browse sources with filters, open details, see episodes/chapters, add to the library and change settings, for both anime and manga. Tauri starts the backend sidecar and the UI connects to it.

## 2. Depends on / inputs

- Phases 1–2: repo/extension/source/anime API endpoints and `/ws`.
- Existing scaffold in `app/`: Vue 3, Vite, TS, Pinia, Vue Router, TanStack vue-query, vitest, ESLint/Prettier.
- Phase 0 S5 sidecar code in `app/src-tauri/src/lib.rs`.

## 3. Deliverables

- `app/src-tauri/src/sidecar.rs`: spawn the backend, parse the port, health-check it, restart it on crash, and expose the `get_backend_info` command.
- `app/src/api/`: typed client (`fetch` wrapper with auth token + base URL), types mirroring backend models, WebSocket client.
- `app/src/composables/`: vue-query hooks (`useSources`, `usePopular`, `useAnime`, `useEpisodes`, …).
- Screens in `app/src/views/`: Library, Browse (source list), Source (popular/latest/search), Details (anime & manga), Extensions, Repos, Settings.
- Components in `app/src/components/`: `FilterRenderer`, `PreferenceRenderer`, `TrustDialog`, `MediaCard`, `VirtualGrid`, `EpisodeList`, `ChapterList`.
- Component tests in `app/src/**/__tests__/`.

## 4. Detailed steps

### Shell & sidecar
1. Move the sidecar logic into `sidecar.rs`: start the backend (dev: `java -jar` from `server/` build output; prod: bundled JRE in Phase 7), pass `--data-dir` and a random auth token, then wait for `MADOYOMI_PORT`.
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
- [ ] `sidecar.rs`: spawn, port handshake, token
- [ ] Health check + restart on crash
- [ ] `get_backend_info` command
- [ ] Startup splash + failure screen

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
- [ ] Killing the backend process shows an error and recovers automatically.

## 7. Risks & notes

- Huge libraries and episode lists: virtualize from the start, not later.
- Filter state must round-trip exactly; keep backend + UI tests in sync with shared fixtures.
- Keep the auth token out of logs and URLs (use a header).

## 8. Decisions closed here

- UI component approach (hand-rolled vs. a component library such as PrimeVue/Naive UI). Decide at the start of this phase.
- Theme/design tokens for the app.
